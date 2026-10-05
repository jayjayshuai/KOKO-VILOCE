package cn.kokonexus.outbox.binding;

import cn.kokonexus.common.diagnostics.SafeFailureDetails;
import cn.kokonexus.outbox.persistence.BindingReleaseMapper;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 有限次异步释放；进程重启后从事实库领取，重复 RPC 由资产结束标记幂等处理。 */
@RequiredArgsConstructor
public class BindingReleaseRelay {

    /** 日志不输出请求内容、用户身份或供应商原始异常。 */
    private static final Logger LOG = LoggerFactory.getLogger(BindingReleaseRelay.class);
    /** 当前领域库的租约和状态映射器。 */
    private final BindingReleaseMapper mapper;
    /** 域提供的 sealBinding 适配器；旧资产 Provider 缺方法不能当作成功。 */
    private final Consumer<BindingRelease> sender;

    /** 单批四行，六十秒租约；RPC 必须保持五秒超时/零重试，禁止占用 Netty EventLoop。 */
    @Scheduled(fixedDelayString = "${koko.asset-binding.release-poll-ms:5000}")
    public void tick() {
        String token = UUID.randomUUID().toString();
        try {
            mapper.exhaustExpired();
            if (mapper.claim(token) == 0) return;
            for (BindingRelease release : mapper.claimed(token)) {
                try {
                    sender.accept(release);
                } catch (RuntimeException failure) {
                    LOG.warn(
                        "资产释放RPC未确认：requestId={}，failure={}",
                        release.getRequestId(),
                        SafeFailureDetails.describe(failure)
                    );
                    int attempts = release.getGenerationAttempts();
                    int delay = Math.min(1 << Math.min(attempts, 10), 900);
                    if (mapper.failed(release.getRequestId(), token, attempts >= 10, delay) != 1) {
                        LOG.warn("资产释放故障确认丢失租约：requestId={}", release.getRequestId());
                    }
                    continue;
                }
                // RPC 已成功但 SQL 确认失败时保留租约，恢复后可重复 seal，不伪装为供应商故障。
                if (mapper.sent(release.getRequestId(), token) != 1) {
                    LOG.warn("资产释放成功确认丢失租约：requestId={}", release.getRequestId());
                }
            }
        } catch (RuntimeException failure) {
            LOG.error("资产释放轮询失败：failure={}", SafeFailureDetails.describe(failure));
        }
    }
}
