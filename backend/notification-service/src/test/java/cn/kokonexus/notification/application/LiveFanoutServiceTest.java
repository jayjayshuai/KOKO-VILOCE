package cn.kokonexus.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.notification.domain.FanoutJob;
import cn.kokonexus.notification.domain.NotificationEvent;
import cn.kokonexus.notification.infrastructure.persistence.NotificationFanoutMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LiveFanoutServiceTest {

    private final NotificationFanoutMapper mapper = mock(NotificationFanoutMapper.class);
    private final NotificationApplicationService notifications = mock(NotificationApplicationService.class);
    private final IdentityRpcService identities = mock(IdentityRpcService.class);
    private final LiveFanoutService service = new LiveFanoutService(mapper, notifications);

    @Test
    void eventAcceptanceIsIdempotent() {
        NotificationEvent event = broadcast();
        service.accept(event);
        service.accept(event);
        verify(mapper, org.mockito.Mockito.times(2)).insertIdempotent(event);
    }

    @Test
    void fanoutDeliversPageAndAdvancesCursor() {
        ReflectionTestUtils.setField(service, "identityRpcService", identities);
        FanoutJob job = job(0L, 1);
        when(mapper.claim(anyString())).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(job);
        when(identities.pageFollowerIds("10", "0", 100)).thenReturn(List.of("20", "30"));

        service.tick();

        verify(notifications).deliver(
            new NotificationEvent(job.getEventId(), 20L, 10L, "LIVE_STARTED", "50", "直播开始")
        );
        verify(notifications).deliver(
            new NotificationEvent(job.getEventId(), 30L, 10L, "LIVE_STARTED", "50", "直播开始")
        );
        verify(mapper).advance(eq(job.getEventId()), anyString(), eq(30L), eq(true));
    }

    @Test
    void rpcFailureSchedulesRetryWithoutAckingJob() {
        ReflectionTestUtils.setField(service, "identityRpcService", identities);
        FanoutJob job = job(30L, 2);
        when(mapper.claim(anyString())).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(job);
        when(identities.pageFollowerIds("10", "30", 100)).thenThrow(new IllegalStateException("offline"));

        service.tick();

        verify(mapper).fail(eq(job.getEventId()), anyString(), eq(false), eq(4), anyString());
    }

    @Test
    void rejectsBroadcastWithRecipient() {
        assertThatThrownBy(() ->
            service.accept(
                new NotificationEvent(UUID.randomUUID().toString(), 20L, 10L, "LIVE_STARTED", "50", "直播开始")
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private NotificationEvent broadcast() {
        return new NotificationEvent(UUID.randomUUID().toString(), 0L, 10L, "LIVE_STARTED", "50", "直播开始");
    }

    private FanoutJob job(long cursor, int attempts) {
        FanoutJob job = new FanoutJob();
        job.setEventId(UUID.randomUUID().toString());
        job.setActorId(10L);
        job.setResourceId("50");
        job.setSummary("直播开始");
        job.setFollowerCursor(cursor);
        job.setAttempts(attempts);
        return job;
    }
}
