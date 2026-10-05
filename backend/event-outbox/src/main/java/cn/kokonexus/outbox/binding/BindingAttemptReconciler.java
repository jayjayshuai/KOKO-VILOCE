package cn.kokonexus.outbox.binding;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 默认关闭的新协议孤立OPEN核对；不访问资产库或执行对象删除。 */
@RequiredArgsConstructor
public class BindingAttemptReconciler {

    /** 固定类别日志，数据库故障保留原凭据下轮重试。 */
    private static final Logger LOG = LoggerFactory.getLogger(BindingAttemptReconciler.class);
    /** 每轮经独立事务代理取得同行锁，不能自调用绕过事务。 */
    private final BindingAttemptResolution resolution;

    /** 一轮最多八行，业务持锁则跳过，不把扫描年龄当到期。 */
    @Scheduled(fixedDelayString = "${koko.asset-binding.attempt-poll-ms:30000}")
    public void tick() {
        try {
            resolution.reconcile();
        } catch (RuntimeException failure) {
            LOG.error("绑定凭据核对失败：failureType={}", failure.getClass().getSimpleName());
        }
    }
}
