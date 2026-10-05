package cn.kokonexus.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.kokonexus.notification.infrastructure.persistence.NotificationFanoutMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class NotificationQueueMetricsTest {

    @Test
    void fanoutQueueReportsBacklogAndSamplingFailure() {
        NotificationFanoutMapper mapper = mock(NotificationFanoutMapper.class);
        when(mapper.countOutstanding()).thenReturn(2L).thenThrow(new IllegalStateException("database offline"));
        when(mapper.countDead()).thenReturn(0L);
        when(mapper.oldestOutstandingAgeSeconds()).thenReturn(35L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationQueueMetrics metrics = new NotificationQueueMetrics(mapper, registry);
        metrics.refresh();
        assertThat(registry.get("koko.notification.fanout.outstanding").gauge().value()).isEqualTo(2);
        assertThat(registry.get("koko.notification.fanout.oldest.age.seconds").gauge().value()).isEqualTo(35);
        assertThat(registry.get("koko.notification.fanout.telemetry.up").gauge().value()).isEqualTo(1);

        metrics.refresh();
        assertThat(registry.get("koko.notification.fanout.outstanding").gauge().value()).isEqualTo(-1);
        assertThat(registry.get("koko.notification.fanout.telemetry.up").gauge().value()).isEqualTo(0);
    }
}
