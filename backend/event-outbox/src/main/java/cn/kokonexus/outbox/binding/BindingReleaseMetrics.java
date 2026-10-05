package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 同一有界 SQL 采样；未知值为 -1 而非零，不因 scrape 触发 SQL，不使用资产或账号标签。 */
public class BindingReleaseMetrics {

    /** 仅记录固定失败说明，不打印 SQL、节点、资产或原异常。 */
    private static final Logger LOG = LoggerFactory.getLogger(BindingReleaseMetrics.class);
    /** 同库采样映射，不注册在不含绑定表的直播域。 */
    private final BindingReleaseReadMapper mapper;
    /** 成功时原子替换完整采样，失败时清空；未开始也为空。 */
    private final AtomicReference<BindingReleaseSnapshot> sample = new AtomicReference<>();
    /** 最近成功采样的单调时钟起点，用于识别调度停滞，不使用数据库时区转换。 */
    private volatile long sampledAtNanos;

    public BindingReleaseMetrics(BindingReleaseReadMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        Gauge.builder("koko.binding.release.backlog", sample, s -> value(s, "PENDING"))
            .tag("status", "PENDING")
            .description("Capped pending release count; -1 unknown")
            .register(registry);
        Gauge.builder("koko.binding.release.backlog", sample, s -> value(s, "LEASED"))
            .tag("status", "LEASED")
            .description("Capped leased release count; -1 unknown")
            .register(registry);
        Gauge.builder("koko.binding.release.backlog", sample, s -> value(s, "DEAD"))
            .tag("status", "DEAD")
            .description("Capped dead release count; -1 unknown")
            .register(registry);
        Gauge.builder("koko.binding.release.oldest.age.seconds", sample, s -> age(s, false))
            .tag("status", "PENDING")
            .register(registry);
        Gauge.builder("koko.binding.release.oldest.age.seconds", sample, s -> age(s, true))
            .tag("status", "DEAD")
            .register(registry);
        Gauge.builder("koko.binding.release.telemetry.up", sample, s -> s.get() == null ? 0 : 1).register(registry);
        Gauge.builder("koko.binding.release.sample.limit", () -> 1001).register(registry);
        Gauge.builder("koko.binding.release.sample.age.seconds", this, metrics ->
            metrics.sample.get() == null
                ? -1
                : Math.max(0, (System.nanoTime() - metrics.sampledAtNanos) / 1_000_000_000.0)
        ).register(registry);
    }

    /** 固定延迟有界采样，失败不阻断业务提交或改变任务；告警接收方需另行配置。 */
    @Scheduled(
        initialDelayString = "${koko.asset-binding.metrics-initial-delay-ms:5000}",
        fixedDelayString = "${koko.asset-binding.metrics-poll-ms:30000}"
    )
    public void refresh() {
        try {
            var current = java.util.Objects.requireNonNull(mapper.snapshot());
            sampledAtNanos = System.nanoTime();
            sample.set(current);
        } catch (RuntimeException unavailable) {
            sample.set(null);
            LOG.warn("Binding release telemetry sample unavailable");
        }
    }

    private static double age(AtomicReference<BindingReleaseSnapshot> source, boolean dead) {
        var current = source.get();
        return current == null ? -1 : dead ? current.oldestDeadAgeSeconds() : current.oldestPendingAgeSeconds();
    }

    private static double value(AtomicReference<BindingReleaseSnapshot> source, String status) {
        var current = source.get();
        if (current == null) return -1;
        return switch (status) {
            case "PENDING" -> current.pending();
            case "LEASED" -> current.leased();
            case "DEAD" -> current.dead();
            default -> throw new IllegalArgumentException("未知采样状态");
        };
    }
}
