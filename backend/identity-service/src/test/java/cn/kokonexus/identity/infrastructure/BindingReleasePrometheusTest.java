package cn.kokonexus.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.outbox.binding.BindingReleaseMetrics;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 实际 Prometheus exporter 验证告警使用的指标名/标签，不用 SimpleRegistry 推测导出格式。 */
class BindingReleasePrometheusTest {

    @Test
    void scrapeExportsExactRuleMetricsWithoutSqlOrPrivateLabels() {
        var mapper = mock(BindingReleaseReadMapper.class);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new BindingReleaseMetrics(mapper, registry);
            assertThat(registry.scrape())
                .contains("koko_binding_release_telemetry_up 0.0")
                .contains("koko_binding_release_backlog{status=\"DEAD\"} -1.0");
            verifyNoInteractions(mapper);
            when(mapper.snapshot()).thenReturn(
                new BindingReleaseSnapshot(1001, 1, 2, 1001, 30, 60, LocalDateTime.now())
            );
            metrics.refresh();
            assertThat(registry.scrape())
                .contains("koko_binding_release_telemetry_up 1.0")
                .contains("koko_binding_release_backlog{status=\"DEAD\"} 2.0")
                .contains("koko_binding_release_oldest_age_seconds{status=\"PENDING\"} 30.0")
                .contains("koko_binding_release_sample_age_seconds ")
                .contains("koko_binding_release_sample_limit 1001.0")
                .doesNotContain("ownerId", "assetId", "requestId", "leaseToken");
            verify(mapper).snapshot();
        } finally {
            registry.close();
        }
    }
}
