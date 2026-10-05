package cn.kokonexus.notification.infrastructure;

import cn.kokonexus.notification.infrastructure.persistence.NotificationFanoutMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 直播粉丝扇出任务的数据库水位；采样失败时显式暴露不可用，不能继续呈现旧的零积压值。
 */
@Component
@ConditionalOnProperty(prefix = "koko.notification.consumer", name = "enabled", havingValue = "true")
public class NotificationQueueMetrics {

    /** 本类诊断日志，禁止输出密码、令牌和业务正文。 */
    private static final Logger log = LoggerFactory.getLogger(NotificationQueueMetrics.class);
    /** NotificationFanoutMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final NotificationFanoutMapper mapper;
    /** 待投递数量；-1 表示未成功采集。 */
    private final AtomicLong outstanding = new AtomicLong(-1);
    /** 永久失败数量；-1 表示未成功采集。 */
    private final AtomicLong dead = new AtomicLong(-1);
    /** 最旧待处理事件年龄，秒。 */
    private final AtomicLong oldestAgeSeconds = new AtomicLong(-1);
    /** 遥测查询是否成功，1 成功/0 失败。 */
    private final AtomicLong telemetryUp = new AtomicLong(0);

    public NotificationQueueMetrics(NotificationFanoutMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        Gauge.builder("koko.notification.fanout.outstanding", outstanding, AtomicLong::get)
            .description("Live fanout jobs not completed")
            .register(registry);
        Gauge.builder("koko.notification.fanout.dead", dead, AtomicLong::get)
            .description("Live fanout jobs exhausted by retries")
            .register(registry);
        Gauge.builder("koko.notification.fanout.oldest.age.seconds", oldestAgeSeconds, AtomicLong::get)
            .description("Age of the oldest incomplete live fanout job")
            .register(registry);
        Gauge.builder("koko.notification.fanout.telemetry.up", telemetryUp, AtomicLong::get)
            .description("Whether the latest fanout database sample succeeded")
            .register(registry);
    }

    @Scheduled(
        initialDelayString = "${koko.notification.metrics.initial-delay-ms:5000}",
        fixedDelayString = "${koko.notification.metrics.poll-ms:30000}"
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
            log.error("Notification fanout telemetry sample failed", exception);
        }
    }
}
