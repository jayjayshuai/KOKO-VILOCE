package cn.kokonexus.outbox;

import cn.kokonexus.common.diagnostics.SafeFailureDetails;
import cn.kokonexus.outbox.persistence.OutboxMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 平台公共契约：OutboxRelay 领域类型；字段单位、状态及可空性见各属性说明。 */
public class OutboxRelay {

    /** 本类诊断日志，禁止输出密码、令牌和业务正文。 */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** OutboxMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final OutboxMapper mapper;
    /** EventSender 外部或领域适配器，失败不伪装为业务成功。 */
    private final EventSender sender;

    public OutboxRelay(OutboxMapper mapper, EventSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${koko.outbox.poll-ms:5000}")
    public void tick() {
        String claimToken = UUID.randomUUID().toString();
        try {
            mapper.exhaustExpired();
            if (mapper.claim(claimToken, 10) == 0) return;
            for (OutboxRecord record : mapper.selectClaimed(claimToken)) {
                try {
                    sender.send(record.toEvent());
                } catch (Exception exception) {
                    int attempts = record.getAttempts() == null ? 1 : record.getAttempts();
                    boolean dead = attempts >= 10;
                    int backoff = Math.min(1 << Math.min(attempts, 10), 900);
                    String error = SafeFailureDetails.describe(exception);
                    if (mapper.markFailed(record.getId(), claimToken, dead, backoff, error) != 1) {
                        log.warn("Outbox failure acknowledgement lost lease for event {}", record.getId());
                        continue;
                    }
                    log.warn(
                        "Outbox delivery {} for event {} after {} attempts; failure={}",
                        dead ? "dead-lettered" : "retry scheduled",
                        record.getId(),
                        attempts,
                        error
                    );
                    continue;
                }
                // 已发送后的SQL确认故障不冒充Broker失败；保留租约以便后续幂等重投。
                if (mapper.markSent(record.getId(), claimToken) != 1) {
                    log.warn("Outbox acknowledgement lost lease for event {}", record.getId());
                }
            }
        } catch (Exception exception) {
            log.error("Outbox relay poll failed; failure={}", SafeFailureDetails.describe(exception));
        }
    }
}
