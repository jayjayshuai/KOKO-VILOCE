package cn.kokonexus.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.kokonexus.outbox.persistence.OutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {

    @Test
    void sampleExposesDatabaseStateAndNeverKeepsStaleZeroAfterFailure() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        when(mapper.countOutstanding()).thenReturn(3L).thenThrow(new IllegalStateException("database offline"));
        when(mapper.countDead()).thenReturn(1L);
        when(mapper.oldestOutstandingAgeSeconds()).thenReturn(75L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(mapper, registry);
        metrics.refresh();
        assertThat(registry.get("koko.outbox.outstanding").gauge().value()).isEqualTo(3);
        assertThat(registry.get("koko.outbox.dead").gauge().value()).isEqualTo(1);
        assertThat(registry.get("koko.outbox.oldest.age.seconds").gauge().value()).isEqualTo(75);
        assertThat(registry.get("koko.outbox.telemetry.up").gauge().value()).isEqualTo(1);

        metrics.refresh();
        assertThat(registry.get("koko.outbox.outstanding").gauge().value()).isEqualTo(-1);
        assertThat(registry.get("koko.outbox.dead").gauge().value()).isEqualTo(-1);
        assertThat(registry.get("koko.outbox.telemetry.up").gauge().value()).isEqualTo(0);
    }
}
