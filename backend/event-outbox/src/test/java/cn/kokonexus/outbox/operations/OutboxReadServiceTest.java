package cn.kokonexus.outbox.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.outbox.persistence.OutboxOperationsMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 查询投影和参数分支单元验收；SQL 复合游标/索引由三库真实验收另行证明。 */
class OutboxReadServiceTest {

    /** 固定虚拟事件 ID。 */
    private static final String EVENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    /** 精确保留微秒的业务库时间夹具。 */
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 3, 12, 0, 0, 123456000);
    /** 固定 SQL 边界。 */
    private final OutboxOperationsMapper mapper = mock(OutboxOperationsMapper.class);
    /** 当前权限边界。 */
    private final OutboxOperationsAuthorizer authority = mock(OutboxOperationsAuthorizer.class);
    /** 真实查询用例分支，无 mock 事务证明。 */
    private final OutboxReadService reads = new OutboxReadService(mapper, authority);

    @Test
    void noPermissionCannotReadEvenKnownEventOrCursor() {
        doThrow(new OperationsAccessDeniedException("isolated denied"))
            .when(authority)
            .requirePermission(10, OutboxReadService.READ_PERMISSION);
        assertThatThrownBy(() -> reads.dead(10, null, 20)).isInstanceOf(OperationsAccessDeniedException.class);
        assertThatThrownBy(() -> reads.detail(10, EVENT)).isInstanceOf(OperationsAccessDeniedException.class);
        assertThatThrownBy(() -> reads.receipt(10, EVENT, EVENT)).isInstanceOf(OperationsAccessDeniedException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void deadPageUsesLookaheadAndLastVisibleCompositeCursor() {
        var second = event("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(mapper.dead(null, 3)).thenReturn(
            List.of(event(EVENT), second, event("cccccccc-cccc-cccc-cccc-cccccccccccc"))
        );
        var page = reads.dead(10, null, 2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo(new OutboxEventCursor(TIME, second.getId()));
        assertThat(page.items().getFirst().totalAttempts()).isEqualTo("9007199254740993");
        assertThat(page.items().getFirst().recipientId()).isEqualTo("9007199254740993");
        assertThat(page.items().getFirst().createdAt()).isEqualTo(TIME);
    }

    @Test
    void lastPageHasNoCursorAndNonDeadDetailsRemainReadable() {
        when(mapper.dead(null, 3)).thenReturn(List.of(event(EVENT)));
        assertThat(reads.dead(10, null, 2).nextCursor()).isNull();
        var sent = event(EVENT);
        sent.setStatus("SENT");
        when(mapper.detail(EVENT)).thenReturn(sent);
        assertThat(reads.detail(10, EVENT.toUpperCase(java.util.Locale.ROOT)).status()).isEqualTo("SENT");
    }

    @Test
    void invalidSizesIncompletePreciseOrOutOfDatabaseRangeCursorRejectBeforeSql() {
        for (int size : List.of(0, 51, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> reads.dead(10, null, size)).isInstanceOf(IllegalArgumentException.class);
        }
        for (var cursor : List.of(
            new OutboxEventCursor(null, EVENT),
            new OutboxEventCursor(TIME, "1-1-1-1-1"),
            new OutboxEventCursor(TIME.withNano(1), EVENT),
            new OutboxEventCursor(TIME.withYear(999), EVENT)
        )) {
            assertThatThrownBy(() -> reads.dead(10, cursor, 2)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> reads.audits(10, EVENT, 12L, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reads.audits(10, EVENT, null, 21)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void auditPageUsesGenerationLookaheadAndPreservesSnapshots() {
        when(mapper.detail(EVENT)).thenReturn(event(EVENT));
        when(mapper.audits(EVENT, null, 3)).thenReturn(List.of(audit(3), audit(2), audit(1)));
        var page = reads.audits(10, EVENT, null, 2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextGeneration()).isEqualTo(2);
        assertThat(page.items().getFirst().previousError()).isEqualTo("isolated failure");
        assertThat(page.items().getFirst().totalAttemptsSnapshot()).isEqualTo("9007199254740993");
    }

    @Test
    void missingReceiptAndEventAreNotAnEmptySuccessfulResult() {
        assertThatThrownBy(() -> reads.detail(10, EVENT)).isInstanceOf(OperationsNotFoundException.class);
        assertThatThrownBy(() -> reads.receipt(10, EVENT, EVENT)).isInstanceOf(OperationsNotFoundException.class);
        when(mapper.receipt(EVENT, EVENT)).thenReturn(audit(1));
        assertThat(reads.receipt(10, EVENT, EVENT).acceptedGeneration()).isEqualTo(1);
        verify(mapper).detail(EVENT);
    }

    private static OutboxOperationsEvent event(String id) {
        var row = new OutboxOperationsEvent();
        row.setId(id);
        row.setEventType("FOLLOW");
        row.setRecipientId(9007199254740993L);
        row.setActorId(10L);
        row.setResourceId("fixture");
        row.setSummary("isolated fixture");
        row.setStatus("DEAD");
        row.setAttempts(10);
        row.setTotalAttempts(9007199254740993L);
        row.setReplayGeneration(0L);
        row.setCreatedAt(TIME);
        row.setNextAttemptAt(TIME);
        return row;
    }

    private static OutboxReplayAudit audit(long generation) {
        var row = new OutboxReplayAudit();
        row.setRequestId(EVENT);
        row.setEventId(EVENT);
        row.setOperatorId(10L);
        row.setExpectedGeneration(generation - 1);
        row.setAcceptedGeneration(generation);
        row.setReason("隔离审计原因不少于十个字符");
        row.setPreviousAttempts(10);
        row.setTotalAttemptsSnapshot(9007199254740993L);
        row.setPreviousError("isolated failure");
        row.setCreatedAt(TIME);
        return row;
    }
}
