package cn.kokonexus.outbox.binding;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.outbox.persistence.BindingReleaseMapper;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 工作循环分支；租约 CAS 与业务同事务需要真实 MySQL 验证。 */
class BindingReleaseRelayTest {

    private final BindingReleaseMapper mapper = mock(BindingReleaseMapper.class);

    @SuppressWarnings("unchecked")
    private final Consumer<BindingRelease> sender = mock(Consumer.class);

    private final BindingReleaseRelay relay = new BindingReleaseRelay(mapper, sender);

    @Test
    void sendsOutsideClaimAndAcknowledgesOnlyItsToken() {
        var release = ready(1);
        when(mapper.claim(anyString())).thenReturn(1);
        when(mapper.claimed(anyString())).thenReturn(List.of(release));
        when(mapper.sent(eq("request"), anyString())).thenReturn(1);
        relay.tick();
        var order = inOrder(mapper, sender);
        order.verify(mapper).exhaustExpired();
        order.verify(mapper).claim(anyString());
        order.verify(mapper).claimed(anyString());
        order.verify(sender).accept(release);
        order.verify(mapper).sent(eq("request"), anyString());
        verify(mapper, never()).failed(anyString(), anyString(), anyBoolean(), anyInt());
    }

    @Test
    void unconfirmedRpcBecomesDeadAtTenWithoutResetOrRawError() {
        var release = ready(10);
        when(mapper.claim(anyString())).thenReturn(1);
        when(mapper.claimed(anyString())).thenReturn(List.of(release));
        doThrow(new IllegalStateException("must-not-persist-sensitive-detail")).when(sender).accept(release);
        relay.tick();
        verify(mapper).failed(eq("request"), anyString(), eq(true), eq(900));
        verify(mapper, never()).sent(anyString(), anyString());
    }

    @Test
    void sqlAcknowledgementFailureIsNotReclassifiedAsRemoteFailure() {
        when(mapper.claim(anyString())).thenReturn(1);
        when(mapper.claimed(anyString())).thenReturn(List.of(ready(1)));
        when(mapper.sent(anyString(), anyString())).thenThrow(new IllegalStateException("simulated SQL failure"));
        relay.tick();
        verify(sender).accept(any());
        verify(mapper, never()).failed(anyString(), anyString(), anyBoolean(), anyInt());
    }

    @Test
    void exhaustedCrashRecoveryRunsEvenWhenNoClaimIsAvailable() {
        relay.tick();
        verify(mapper).exhaustExpired();
        verify(mapper).claim(anyString());
        verify(mapper, never()).claimed(anyString());
        verifyNoInteractions(sender);
    }

    private BindingRelease ready(int attempts) {
        var release = new BindingRelease();
        release.setRequestId("request");
        release.setAttempts(attempts);
        release.setGenerationAttempts(attempts);
        release.setReplayGeneration(0);
        return release;
    }
}
