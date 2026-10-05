package cn.kokonexus.outbox.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.outbox.persistence.OutboxReplayMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** 单元行为不替代真实 SQL/事务证明；并发与审计失败回滚在隔离 MySQL 另外验收。 */
class OutboxReplayServiceTest {

    private static final String EVENT = "30458721-5f3e-4fef-b706-471ab7958bc6";
    private static final String REQUEST = "ae196123-043b-47c2-a68c-4438976874fb";
    private static final String REASON = "Broker 已恢复，关联工单 20261003";
    private final OutboxReplayMapper mapper = mock(OutboxReplayMapper.class);
    private final OutboxOperationsAuthorizer authorizer = mock(OutboxOperationsAuthorizer.class);
    private final OutboxReplayService service = new OutboxReplayService(mapper, authorizer);

    @Test
    void deniedPermissionNeverReadsOrMutatesEvent() {
        doThrow(new ForbiddenOperationException("无权限"))
            .when(authorizer)
            .requirePermission(10, service.REPLAY_PERMISSION);
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidInputFailsBeforeSql() {
        assertThatThrownBy(() -> service.replay(0, command())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replay(10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            service.replay(10, new OutboxReplayCommand("1-2-3-4-5", EVENT, 0, REASON))
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replay(10, new OutboxReplayCommand(REQUEST, EVENT, -1, REASON))).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> service.replay(10, new OutboxReplayCommand(REQUEST, EVENT, 0, "短原因"))).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(mapper);
    }

    @Test
    void missingEventIsNotFound() {
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(ResourceNotFoundException.class);
        verify(mapper, never()).requeue(any(), anyLong());
    }

    @Test
    void nonDeadOrClaimedOrStaleGenerationCannotBeReplayed() {
        for (String status : new String[] { "SENT", "PENDING", "RETRY", "IN_FLIGHT" }) {
            var state = dead();
            state.setStatus(status);
            when(mapper.lockEvent(EVENT)).thenReturn(state);
            assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(IllegalStateException.class);
        }
        var claimed = dead();
        claimed.setClaimToken("old-claim");
        when(mapper.lockEvent(EVENT)).thenReturn(claimed);
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(IllegalStateException.class);
        claimed.setClaimToken(null);
        claimed.setLeaseUntil(LocalDateTime.now());
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(IllegalStateException.class);
        claimed.setLeaseUntil(null);
        claimed.setReplayGeneration(1L);
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).requeue(any(), anyLong());
    }

    @Test
    void receivedCommandRequeuesAndStoresOriginalFailureInAudit() {
        when(mapper.lockEvent(EVENT)).thenReturn(dead());
        when(mapper.requeue(EVENT, 0)).thenReturn(1);
        when(mapper.insertAudit(any())).thenReturn(1);
        when(mapper.findAudit(REQUEST)).thenReturn(null, accepted());
        var result = service.replay(10, command());
        assertThat(result.generation()).isEqualTo(1);
        assertThat(result.acceptedAt()).isEqualTo(accepted().getCreatedAt());
        var audit = org.mockito.ArgumentCaptor.forClass(OutboxReplayAudit.class);
        verify(mapper).insertAudit(audit.capture());
        assertThat(audit.getValue().getPreviousAttempts()).isEqualTo(10);
        assertThat(audit.getValue().getTotalAttemptsSnapshot()).isEqualTo(10);
        assertThat(audit.getValue().getPreviousError()).isEqualTo("Broker offline");
        verify(authorizer).requirePermission(10, service.REPLAY_PERMISSION);
    }

    @Test
    void duplicateReturnsOriginalReceiptEvenAfterEventSent() {
        var state = dead();
        state.setStatus("SENT");
        state.setReplayGeneration(2L);
        when(mapper.lockEvent(EVENT)).thenReturn(state);
        when(mapper.findAudit(REQUEST)).thenReturn(accepted());
        assertThat(service.replay(10, command()).generation()).isEqualTo(1);
        verify(mapper, never()).requeue(any(), anyLong());
        verify(mapper, never()).insertAudit(any());
    }

    @Test
    void requestCannotBeReusedByDifferentActorReasonVersionOrEvent() {
        when(mapper.lockEvent(any())).thenReturn(dead());
        when(mapper.findAudit(REQUEST)).thenReturn(accepted());
        assertThatThrownBy(() -> service.replay(11, command())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
            service.replay(10, new OutboxReplayCommand(REQUEST, EVENT, 0, "不同工单与不同重放原因"))
        ).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.replay(10, new OutboxReplayCommand(REQUEST, EVENT, 1, REASON))).isInstanceOf(
            IllegalStateException.class
        );
        assertThatThrownBy(() ->
            service.replay(10, new OutboxReplayCommand(REQUEST, "30458721-5f3e-4fef-b706-471ab7958bc7", 0, REASON))
        ).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).requeue(any(), anyLong());
    }

    @Test
    void generationCapRequiresRootCauseInvestigation() {
        var state = dead();
        state.setReplayGeneration(10L);
        when(mapper.lockEvent(EVENT)).thenReturn(state);
        assertThatThrownBy(() -> service.replay(10, new OutboxReplayCommand(REQUEST, EVENT, 10, REASON)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("十轮");
        verify(mapper, never()).requeue(any(), anyLong());
    }

    @Test
    void failedConditionalUpdateDoesNotAppendAudit() {
        when(mapper.lockEvent(EVENT)).thenReturn(dead());
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).insertAudit(any());
    }

    @Test
    void auditConflictIsThrownNotSwallowed() {
        when(mapper.lockEvent(EVENT)).thenReturn(dead());
        when(mapper.requeue(EVENT, 0)).thenReturn(1);
        when(mapper.insertAudit(any())).thenThrow(new DuplicateKeyException("race"));
        assertThatThrownBy(() -> service.replay(10, command()))
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void missingPersistedAuditIsNotAccepted() {
        when(mapper.lockEvent(EVENT)).thenReturn(dead());
        when(mapper.requeue(EVENT, 0)).thenReturn(1);
        when(mapper.insertAudit(any())).thenReturn(1);
        assertThatThrownBy(() -> service.replay(10, command())).isInstanceOf(IllegalStateException.class);
    }

    private OutboxReplayCommand command() {
        return new OutboxReplayCommand(REQUEST, EVENT, 0, REASON);
    }

    private OutboxReplayState dead() {
        var state = new OutboxReplayState();
        state.setId(EVENT);
        state.setStatus("DEAD");
        state.setAttempts(10);
        state.setTotalAttempts(10L);
        state.setReplayGeneration(0L);
        state.setLastError("Broker offline");
        return state;
    }

    private OutboxReplayAudit accepted() {
        var audit = new OutboxReplayAudit();
        audit.setRequestId(REQUEST);
        audit.setEventId(EVENT);
        audit.setOperatorId(10L);
        audit.setExpectedGeneration(0L);
        audit.setAcceptedGeneration(1L);
        audit.setReason(REASON);
        audit.setCreatedAt(LocalDateTime.of(2026, 10, 3, 20, 0));
        return audit;
    }
}
