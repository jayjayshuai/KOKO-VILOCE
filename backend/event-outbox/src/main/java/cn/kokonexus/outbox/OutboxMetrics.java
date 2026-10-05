package cn.kokonexus.outbox;

import cn.kokonexus.outbox.persistence.OutboxMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 采样数据库中的待投递和死信状态。监控采样失败不影响业务写入或投递，
 * telemetry.up 置零且数值置为 -1，避免旧值被误认为当前队列状态。
 */
public class OutboxMetrics {

    /** 本类诊断日志，禁止输出密码、令牌和业务正文。 */
    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);
    /** OutboxMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final OutboxMapper mapper;
    /** 待投递数量；-1 表示未成功采集。 */
    private final AtomicLong outstanding = new AtomicLong(-1);
    /** 永久失败数量；-1 表示未成功采集。 */
    private final AtomicLong dead = new AtomicLong(-1);
    /** 最旧待处理事件年龄，秒。 */
    private final AtomicLong oldestAgeSeconds = new AtomicLong(-1);
    /** 遥测查询是否成功，1 成功/0 失败。 */
    private final AtomicLong telemetryUp = new AtomicLong(0);

    public OutboxMetrics(OutboxMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        Gauge.builder("koko.outbox.outstanding", outstanding, AtomicLong::get)
            .description("Outbox events not yet acknowledged as sent")
            .register(registry);
        Gauge.builder("koko.outbox.dead", dead, AtomicLong::get)
            .description("Outbox events exhausted by retries")
            .register(registry);
        Gauge.builder("koko.outbox.oldest.age.seconds", oldestAgeSeconds, AtomicLong::get)
            .description("Age of the oldest unacknowledged outbox event")
            .register(registry);
        Gauge.builder("koko.outbox.telemetry.up", telemetryUp, AtomicLong::get)
            .description("Whether the latest outbox database sample succeeded")
            .register(registry);
    }

    @Scheduled(
        initialDelayString = "${koko.outbox.metrics.initial-delay-ms:5000}",
        fixedDelayString = "${koko.outbox.metrics.poll-ms:30000}"
    )
    public void refresh() {
        try {
            long sampledOutstanding = mapper.countOutstanding();
            long sampledDead = mapper.countDead();
            long sampledAge = mapper.oldestOutstandingAgeSeconds();
            outstanding.set(sampledOutstanding);
            dead.set(sampledDead);
            oldestAgeSeconds.set(sampledAge);
            telemetryUp.set(1);
        } catch (Exception exception) {
            outstanding.set(-1);
            dead.set(-1);
            oldestAgeSeconds.set(-1);
            telemetryUp.set(0);
            log.error("Outbox telemetry sample failed", exception);
        }
    }
}
