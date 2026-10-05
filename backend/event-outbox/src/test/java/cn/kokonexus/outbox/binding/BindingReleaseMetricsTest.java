package cn.kokonexus.outbox.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 真 MeterRegistry，SQL 为单元边界；scrape 不允许重新访问数据库或携带高基数标签。 */
class BindingReleaseMetricsTest {

    @Test
    void unknownBeforeStartThenCappedSampleFailureAndRecovery() {
        var mapper = mock(BindingReleaseReadMapper.class);
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new BindingReleaseMetrics(mapper, registry);
            assertThat(registry.get("koko.binding.release.telemetry.up").gauge().value()).isZero();
            assertThat(registry.get("koko.binding.release.sample.age.seconds").gauge().value()).isEqualTo(-1);
            assertThat(registry.get("koko.binding.release.backlog").tag("status", "DEAD").gauge().value()).isEqualTo(
                -1
            );
            verifyNoInteractions(mapper);
            when(mapper.snapshot()).thenReturn(
                new BindingReleaseSnapshot(1001, 1, 2, 1001, 30, 60, LocalDateTime.now())
            );
            metrics.refresh();
            assertThat(registry.get("koko.binding.release.backlog").tag("status", "PENDING").gauge().value()).isEqualTo(
                1001
            );
            assertThat(
                registry.get("koko.binding.release.oldest.age.seconds").tag("status", "DEAD").gauge().value()
            ).isEqualTo(60);
            assertThat(registry.get("koko.binding.release.telemetry.up").gauge().value()).isEqualTo(1);
            assertThat(registry.get("koko.binding.release.sample.age.seconds").gauge().value()).isGreaterThanOrEqualTo(
                0
            );
            registry.forEachMeter(meter ->
                assertThat(meter.getId().getTags()).allMatch(tag -> tag.getKey().equals("status"))
            );
            verify(mapper, times(1)).snapshot();
            when(mapper.snapshot()).thenThrow(new IllegalStateException("private-sql"));
            metrics.refresh();
            assertThat(registry.get("koko.binding.release.telemetry.up").gauge().value()).isZero();
            assertThat(registry.get("koko.binding.release.backlog").tag("status", "PENDING").gauge().value()).isEqualTo(
                -1
            );
            assertThat(
                registry.get("koko.binding.release.oldest.age.seconds").tag("status", "DEAD").gauge().value()
            ).isEqualTo(-1);
            doReturn(new BindingReleaseSnapshot(0, 0, 0, 1001, 0, 0, LocalDateTime.now()))
                .when(mapper)
                .snapshot();
            metrics.refresh();
            assertThat(registry.get("koko.binding.release.backlog").tag("status", "DEAD").gauge().value()).isZero();
            assertThat(registry.get("koko.binding.release.telemetry.up").gauge().value()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
