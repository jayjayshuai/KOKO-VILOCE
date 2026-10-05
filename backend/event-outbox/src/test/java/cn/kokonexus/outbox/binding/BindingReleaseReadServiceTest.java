package cn.kokonexus.outbox.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.outbox.operations.OutboxOperationsAuthorizer;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 只证明命令边界和投影，SQL 排序/采样/迁移需真实 MySQL 验证。 */
class BindingReleaseReadServiceTest {

    /** 隔离 UUID，不是线上资源。 */
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    /** 精确微秒断点。 */
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 4, 12, 0, 0, 123456000);
    /** 查询 SQL 边界桩。 */
    private final BindingReleaseReadMapper mapper = mock(BindingReleaseReadMapper.class);
    /** 当前事实权限边界。 */
    private final OutboxOperationsAuthorizer authority = mock(OutboxOperationsAuthorizer.class);
    /** 单元用例无事务代理。 */
    private final BindingReleaseReadService reads = new BindingReleaseReadService(mapper, authority);

    @Test
    void forbiddenAndUnavailableAuthorityNeverReadDatabase() {
        for (RuntimeException failure : List.of(
            new OperationsAccessDeniedException("fixture"),
            new OperationsUnavailableException("fixture")
        )) {
            doThrow(failure).when(authority).requirePermission(10, "asset:binding:read");
            assertThatThrownBy(() -> reads.dead(10, null, 20)).isSameAs(failure);
            assertThatThrownBy(() -> reads.detail(10, ID)).isSameAs(failure);
            assertThatThrownBy(() -> reads.snapshot(10)).isSameAs(failure);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidCursorLimitActorAndUuidFailBeforeSql() {
        assertThatThrownBy(() -> reads.dead(0, null, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reads.dead(10, null, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reads.dead(10, new BindingReleaseCursor(TIME.plusNanos(1), ID), 20)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> reads.dead(10, new BindingReleaseCursor(null, ID), 20)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> reads.detail(10, "1-1-1-1-1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void lookaheadCursorUsesLastVisibleRowAndDoesNotReturnSecrets() {
        when(mapper.dead(null, 3)).thenReturn(List.of(row(ID), row("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), row(ID)));
        var page = reads.dead(10, null, 2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor().requestId()).isEqualTo(page.items().getLast().requestId());
        assertThat(page.nextCursor().createdAt()).isEqualTo(TIME);
        assertThat(page.items().getFirst().lastFailure()).isEqualTo("unknown");
        assertThat(page.items().getFirst().toString()).doesNotContain(
            "private-lease",
            "internal-sql",
            "9007199254740993"
        );
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizedCursorAndNonDeadDetailAreCurrentFacts() {
        var cursor = new BindingReleaseCursor(TIME, ID.toUpperCase(java.util.Locale.ROOT));
        when(mapper.dead(new BindingReleaseCursor(TIME, ID), 21)).thenReturn(List.of());
        assertThat(reads.dead(10, cursor, 20).nextCursor()).isNull();
        var sent = row(ID);
        sent.setStatus("SENT");
        sent.setLastFailure(null);
        when(mapper.detail(ID)).thenReturn(sent);
        assertThat(reads.detail(10, ID).status()).isEqualTo("SENT");
        assertThatThrownBy(() -> reads.detail(10, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")).isInstanceOf(
            OperationsNotFoundException.class
        );
    }

    @Test
    void snapshotAndFacadeKeepUnavailableDistinctFromEmptyAndDefaultClosed() {
        var closed = new BindingReleaseOperationsFacade(false, reads);
        assertThatThrownBy(() -> closed.snapshot("10")).isInstanceOf(OperationsUnavailableException.class);
        verifyNoInteractions(mapper, authority);
        var opened = new BindingReleaseOperationsFacade(true, reads);
        assertThatThrownBy(() -> opened.snapshot("9223372036854775808")).isInstanceOf(IllegalArgumentException.class);
        when(mapper.snapshot()).thenThrow(new DataAccessResourceFailureException("private-sql"));
        assertThatThrownBy(() -> opened.snapshot("10"))
            .isInstanceOf(OperationsUnavailableException.class)
            .hasMessage("绑定释放持久化暂不可用")
            .hasNoCause();
        var empty = new BindingReleaseSnapshot(0, 0, 0, 1001, 0, 0, TIME);
        doReturn(empty).when(mapper).snapshot();
        assertThat(opened.snapshot("10")).isSameAs(empty);
    }

    private static BindingRelease row(String id) {
        var row = new BindingRelease();
        row.setRequestId(id);
        row.setAssetId(ID);
        row.setOwnerId(9007199254740993L);
        row.setPurpose("AVATAR");
        row.setStatus("DEAD");
        row.setAttempts(10);
        row.setReplayGeneration(0);
        row.setGenerationAttempts(10);
        row.setLastFailure("internal-sql");
        row.setLeaseToken("private-lease");
        row.setCreatedAt(TIME);
        row.setUpdatedAt(TIME);
        row.setNextAttemptAt(TIME);
        return row;
    }
}
