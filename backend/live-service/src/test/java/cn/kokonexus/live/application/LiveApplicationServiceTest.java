package cn.kokonexus.live.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.live.domain.LiveStream;
import cn.kokonexus.live.infrastructure.persistence.LiveStreamMapper;
import cn.kokonexus.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;

class LiveApplicationServiceTest {

    @Test
    void cannotGoLiveWithoutARealMediaProvider() {
        LiveStreamMapper mapper = mock(LiveStreamMapper.class);
        LiveStream stream = new LiveStream();
        stream.setId(3001L);
        stream.setCreatorId(1001L);
        stream.setProvider("UNCONFIGURED");
        when(mapper.selectById(3001L)).thenReturn(stream);

        LiveApplicationService service = new LiveApplicationService(mapper, mock(OutboxWriter.class));

        assertThatThrownBy(() -> service.transition(1001L, 3001L, "LIVE"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("媒体供应商");
        verify(mapper, never()).transitionStatus(3001L, 1001L, "SCHEDULED", "LIVE");
    }

    @Test
    void endUsesCreatorAndExpectedStateAsDatabaseConditions() {
        LiveStreamMapper mapper = mock(LiveStreamMapper.class);
        when(mapper.transitionStatus(3001L, 1001L, "LIVE", "ENDED")).thenReturn(1);

        new LiveApplicationService(mapper, mock(OutboxWriter.class)).transition(1001L, 3001L, "ENDED");

        verify(mapper).transitionStatus(3001L, 1001L, "LIVE", "ENDED");
    }

    @Test
    void successfulLiveTransitionEnqueuesBroadcastInSameServiceTransaction() {
        LiveStreamMapper mapper = mock(LiveStreamMapper.class);
        OutboxWriter writer = mock(OutboxWriter.class);
        LiveStream stream = new LiveStream();
        stream.setId(3001L);
        stream.setCreatorId(1001L);
        stream.setProvider("MEDIA_VENDOR");
        stream.setProviderInputId("input-1");
        stream.setTitle("真实直播");
        when(mapper.selectById(3001L)).thenReturn(stream);
        when(mapper.transitionStatus(3001L, 1001L, "SCHEDULED", "LIVE")).thenReturn(1);

        new LiveApplicationService(mapper, writer).transition(1001L, 3001L, "LIVE");

        verify(writer).enqueueLiveStarted(1001L, 3001L, "真实直播");
    }
}
