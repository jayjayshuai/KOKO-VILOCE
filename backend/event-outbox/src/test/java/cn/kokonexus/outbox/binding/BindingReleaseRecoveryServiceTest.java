package cn.kokonexus.outbox.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.outbox.persistence.BindingReleaseRecoveryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** 分支/授权/快照测试；同事务回滚和竞争必须再用真实 MySQL 验证。 */
class BindingReleaseRecoveryServiceTest {

    private static final String TASK = "81000000-0000-4000-8000-000000000001";
    private static final String COMMAND = "81000000-0000-4000-8000-000000000002";
    private static final String REASON = "隔离工单已经排查并处理根因";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 5, 0, 0, 0, 123456000);
    private final BindingReleaseRecoveryMapper mapper = mock(BindingReleaseRecoveryMapper.class);
    private final BindingReleaseReplayAuthorization authority = mock(BindingReleaseReplayAuthorization.class);
    private final BindingReleaseRecoveryService service = new BindingReleaseRecoveryService(
        "identity",
        mapper,
        authority
    );
    private final BindingReleaseReplayCommand command = new BindingReleaseReplayCommand(COMMAND, TASK, 0, REASON);

    @Test
    void successfulAcceptancePreservesCumulativeSnapshotAndIdempotentSameCommand() {
        var saved = new AtomicReference<BindingReleaseAudit>();
        when(mapper.lockRelease(TASK)).thenReturn(dead());
        when(mapper.findAudit(COMMAND)).thenAnswer(call -> saved.get());
        when(mapper.requeue(TASK, 0)).thenReturn(1);
        when(mapper.insertAudit(any())).thenAnswer(call -> {
            var audit = call.<BindingReleaseAudit>getArgument(0);
            audit.setCreatedAt(TIME);
            saved.set(audit);
            return 1;
        });
        var result = service.replay(10, command);
        assertThat(result.acceptedGeneration()).isEqualTo(1);
        assertThat(saved.get().getPreviousAttempts()).isEqualTo(10);
        assertThat(saved.get().getPreviousGenerationAttempts()).isEqualTo(10);
        assertThat(saved.get().getPreviousFailure()).isEqualTo("unknown");
        assertThat(service.replay(10, command)).isEqualTo(result);
        verify(mapper, times(1)).requeue(TASK, 0);
        verify(mapper, times(1)).insertAudit(any());
    }

    @Test
    void statusLeaseGenerationAndMaximumRejectWithoutWriting() {
        for (int scenario = 0; scenario < 6; scenario++) {
            var task = dead();
            var input = command;
            if (scenario == 0) task.setStatus("SENT");
            if (scenario == 1) task.setLeaseToken("stale-token");
            if (scenario == 2) task.setLeaseUntil(TIME);
            if (scenario == 3) task.setReplayGeneration(1);
            if (scenario == 4) {
                task.setReplayGeneration(10);
                input = new BindingReleaseReplayCommand(COMMAND, TASK, 10, REASON);
            }
            if (scenario == 5) task.setGenerationAttempts(9);
            when(mapper.lockRelease(TASK)).thenReturn(task);
            var value = input;
            assertThatThrownBy(() -> service.replay(10, value)).isInstanceOf(IllegalStateException.class);
        }
        verify(mapper, never()).requeue(anyString(), anyInt());
        verify(mapper, never()).insertAudit(any());
    }

    @Test
    void oldCommandCannotChangeActorReasonTargetOrExpectedGeneration() {
        var audit = audit();
        when(mapper.lockRelease(anyString())).thenReturn(dead());
        when(mapper.findAudit(COMMAND)).thenReturn(audit);
        assertThatThrownBy(() -> service.replay(11, command)).isInstanceOf(IllegalStateException.class);
        for (var input : List.of(
            new BindingReleaseReplayCommand(COMMAND, TASK, 1, REASON),
            new BindingReleaseReplayCommand(COMMAND, TASK, 0, REASON + "不同"),
            new BindingReleaseReplayCommand(COMMAND, COMMAND, 0, REASON)
        )) {
            assertThatThrownBy(() -> service.replay(10, input)).isInstanceOf(IllegalStateException.class);
        }
        verify(mapper, never()).requeue(anyString(), anyInt());
    }

    @Test
    void auditDuplicateAndZeroUpdateAreNotAccepted() {
        when(mapper.lockRelease(TASK)).thenReturn(dead());
        when(mapper.requeue(TASK, 0)).thenReturn(0);
        assertThatThrownBy(() -> service.replay(10, command)).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).insertAudit(any());
        when(mapper.requeue(TASK, 0)).thenReturn(1);
        when(mapper.insertAudit(any())).thenThrow(new DuplicateKeyException("isolated"));
        assertThatThrownBy(() -> service.replay(10, command))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("恢复未提交");
    }

    @Test
    void missingTaskAndReadPermissionNeverInventReceipt() {
        assertThatThrownBy(() -> service.replay(10, command)).isInstanceOf(OperationsNotFoundException.class);
        assertThatThrownBy(() -> service.receipt(10, TASK, COMMAND)).isInstanceOf(OperationsNotFoundException.class);
        when(mapper.findAudit(COMMAND)).thenReturn(audit());
        assertThat(service.receipt(10, TASK, COMMAND).operatorId()).isEqualTo("10");
        assertThatThrownBy(() -> service.receipt(10, COMMAND, COMMAND)).isInstanceOf(OperationsNotFoundException.class);
        verify(authority, times(3)).requirePermission(10, "asset:binding:read");
    }

    @Test
    void boundedAuditPageAndDeniedAuthorization() {
        when(mapper.audits(TASK, null, 2)).thenReturn(List.of(audit(), audit()));
        assertThat(service.audits(10, TASK, null, 1).nextGeneration()).isEqualTo(1);
        assertThatThrownBy(() -> service.audits(10, TASK, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        doThrow(new OperationsAccessDeniedException("denied"))
            .when(authority)
            .requirePermission(11, "asset:binding:replay");
        assertThatThrownBy(() -> service.replay(11, command)).isInstanceOf(OperationsAccessDeniedException.class);
        verify(mapper, never()).lockRelease(TASK);
    }

    @Test
    void facadeDefaultClosedAndDedicatedProofBeforeCore() {
        var core = mock(BindingReleaseRecoveryService.class);
        var closed = new BindingReleaseRecoveryFacade("identity", false, true, core, authority);
        assertThatThrownBy(() -> closed.replay("10", "session", command, "secret")).isInstanceOf(
            OperationsUnavailableException.class
        );
        verifyNoInteractions(core, authority);
        var readOnly = new BindingReleaseRecoveryFacade("identity", true, false, core, authority);
        assertThatThrownBy(() -> readOnly.replay("10", "session", command, "secret")).isInstanceOf(
            OperationsUnavailableException.class
        );
        var opened = new BindingReleaseRecoveryFacade("identity", true, true, core, authority);
        opened.replay("10", "session", command, "secret");
        var order = inOrder(authority, core);
        order.verify(authority).validateProof(10, "session", "identity", command, "secret");
        order.verify(core).replay(10, command);
    }

    private static BindingRelease dead() {
        var task = new BindingRelease();
        task.setRequestId(TASK);
        task.setStatus("DEAD");
        task.setAttempts(10);
        task.setReplayGeneration(0);
        task.setGenerationAttempts(10);
        task.setLastFailure("internal-secret");
        return task;
    }

    private static BindingReleaseAudit audit() {
        var value = new BindingReleaseAudit();
        value.setCommandId(COMMAND);
        value.setRequestId(TASK);
        value.setOperatorId(10L);
        value.setExpectedGeneration(0);
        value.setAcceptedGeneration(1);
        value.setPreviousAttempts(10);
        value.setPreviousGenerationAttempts(10);
        value.setReason(REASON);
        value.setCreatedAt(TIME);
        return value;
    }
}
