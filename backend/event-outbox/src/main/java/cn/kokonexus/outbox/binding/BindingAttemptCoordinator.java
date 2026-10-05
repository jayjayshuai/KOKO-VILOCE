package cn.kokonexus.outbox.binding;

import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 实际业务事务锁住新协议凭据直到结束；核对先封存时迟到写入必须拒绝。 */
@RequiredArgsConstructor
public class BindingAttemptCoordinator {

    /** 日志只记录关联ID和失败类，不泄露用户/资产/秘密。 */
    private static final Logger LOG = LoggerFactory.getLogger(BindingAttemptCoordinator.class);
    /** 独立提交OPEN的代理。 */
    private final BindingAttemptReservation reservation;
    /** 凭据同行锁与同事务终态SQL。 */
    private final BindingAttemptMapper mapper;
    /** 与业务提交同事务的释放任务代理。 */
    private final BindingReleaseWriter writer;
    /** 回滚后独立封存/持久补偿代理。 */
    private final BindingAttemptResolution resolution;

    /** 任意获取/登记异常标记业务回滚；begin未知也保留已提交OPEN，不能降级只读。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void protect(
        long ownerId,
        String assetId,
        String purpose,
        String requestId,
        BooleanSupplier acquire,
        Runnable release
    ) {
        if (
            !TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ) {
            throw new IllegalStateException("绑定凭据要求有效本地写事务");
        }
        reservation.reserve(ownerId, assetId, purpose, requestId);
        var row = mapper.lock(requestId);
        if (
            row == null ||
            !"OPEN".equals(row.getStatus()) ||
            row.getOwnerId() != ownerId ||
            !assetId.equals(row.getAssetId()) ||
            !purpose.equals(row.getPurpose())
        ) {
            throw new IllegalStateException("绑定凭据已封存或指纹不匹配");
        }
        // 在RPC前注册，begin丢回复后的明确回滚也能持久登记补偿；未知事务结果仍不释放。
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_UNKNOWN) {
                        LOG.warn("绑定凭据保留：requestId={}，事务结果未知", requestId);
                        return;
                    }
                    try {
                        if (status == STATUS_ROLLED_BACK && !resolution.abort(requestId)) return;
                        if (status == STATUS_COMMITTED || status == STATUS_ROLLED_BACK) release.run();
                    } catch (RuntimeException failure) {
                        LOG.warn(
                            "绑定凭据待补偿：requestId={}，failureType={}",
                            requestId,
                            failure.getClass().getSimpleName()
                        );
                    }
                }
            }
        );
        if (!acquire.getAsBoolean()) throw new IllegalArgumentException("媒体资产不存在、用途错误或不可绑定");
        writer.stage(ownerId, assetId, purpose, requestId);
        if (mapper.finish(row, "COMMITTED") != 1) throw new IllegalStateException("绑定提交凭据围栏失效");
    }
}
