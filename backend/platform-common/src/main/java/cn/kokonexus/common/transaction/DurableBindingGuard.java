package cn.kokonexus.common.transaction;

import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在本地写事务完成前保留远端持久化绑定意图；未知提交结果不释放，禁止 TTL 自动过期。 */
public final class DurableBindingGuard {

    /** 仅记录关联编号与失败类型，不输出用户资料、密钥或完整 RPC 参数。 */
    private static final Logger LOG = LoggerFactory.getLogger(DurableBindingGuard.class);

    private DurableBindingGuard() {}

    /**
     * 必须经过真实 Spring 写事务代理调用。成功领取后仅在确定提交/回滚时尝试释放；
     * 领取超时或释放失败可能留下意图，宁可阻止清理，也不把未知结果当作不存在。
     */
    public static void acquire(String requestId, BooleanSupplier acquire, Runnable release) {
        acquire(requestId, acquire, () -> {}, release);
    }

    /** 成功领取后在同一本地写事务登记持久化释放任务；登记失败必须使业务事务回滚。 */
    public static void acquire(
        String requestId,
        BooleanSupplier acquire,
        Runnable stageCommittedRelease,
        Runnable release
    ) {
        if (
            !TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ) {
            throw new IllegalStateException("媒体绑定必须在有效的本地写事务中执行");
        }
        if (!acquire.getAsBoolean()) {
            throw new IllegalArgumentException("媒体资产不存在、归属或用途不匹配，或当前不可绑定");
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED && status != STATUS_ROLLED_BACK) {
                        LOG.warn("媒体绑定意图保留：requestId={}，事务结果未知", requestId);
                        return;
                    }
                    try {
                        release.run();
                    } catch (RuntimeException exception) {
                        // 业务事务已经完成；远端释放失败不能把已提交业务改报失败或触发再次写入。
                        LOG.warn(
                            "媒体绑定意图待核对：requestId={}，releaseFailure={}",
                            requestId,
                            exception.getClass().getSimpleName()
                        );
                    }
                }
            }
        );
        stageCommittedRelease.run();
    }
}
