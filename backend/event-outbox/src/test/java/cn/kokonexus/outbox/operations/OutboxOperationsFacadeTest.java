package cn.kokonexus.outbox.operations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.common.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OutboxOperationsFacadeTest {

    /** 合成命令。 */
    private static final OutboxReplayCommand COMMAND = new OutboxReplayCommand(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        0,
        "隔离确认原因不少于十个字符"
    );
    /** 真实查询 Bean 的边界 mock。 */
    private final OutboxReadService reads = mock(OutboxReadService.class);
    /** 真实事务重新排队 Bean 的边界 mock。 */
    private final OutboxReplayService replays = mock(OutboxReplayService.class);
    /** 当前确认与权限事实边界。 */
    private final OutboxDomainAuthorization authority = mock(OutboxDomainAuthorization.class);

    @Test
    void defaultClosedFacadeDoesNotTouchDatabaseOrAuthority() {
        var facade = facade(false);
        assertThatThrownBy(() -> facade.dead("10", null, 20)).isInstanceOf(OperationsUnavailableException.class);
        assertThatThrownBy(() -> facade.detail("10", COMMAND.eventId())).isInstanceOf(
            OperationsUnavailableException.class
        );
        assertThatThrownBy(() -> facade.audits("10", COMMAND.eventId(), null, 10)).isInstanceOf(
            OperationsUnavailableException.class
        );
        assertThatThrownBy(() -> facade.receipt("10", COMMAND.eventId(), COMMAND.requestId())).isInstanceOf(
            OperationsUnavailableException.class
        );
        assertThatThrownBy(() -> facade.replay("10", "session", COMMAND, "proof")).isInstanceOf(
            OperationsUnavailableException.class
        );
        verifyNoInteractions(reads, replays, authority);
    }

    @Test
    void proofValidationUsesFixedServerDomainBeforeCore() {
        facade(true).replay("10", "session", COMMAND, "proof");
        var sequence = inOrder(authority, replays);
        sequence.verify(authority).validateProof(10, "session", "community", COMMAND, "proof");
        sequence.verify(replays).replay(10, COMMAND);
    }

    @Test
    void wrongProofAndMalformedActorCannotEnterCore() {
        doThrow(new OperationsAccessDeniedException("isolated denied"))
            .when(authority)
            .validateProof(10, "session", "community", COMMAND, "proof");
        assertThatThrownBy(() -> facade(true).replay("10", "session", COMMAND, "proof")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        assertThatThrownBy(() -> facade(true).dead("9223372036854775808", null, 20)).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(replays, reads);
    }

    @Test
    void persistedDependencyFailureAndMissingEventAreStableApiExceptions() {
        when(reads.dead(10, null, 20)).thenThrow(new DataAccessResourceFailureException("isolated secret SQL detail"));
        assertThatThrownBy(() -> facade(true).dead("10", null, 20))
            .isInstanceOf(OperationsUnavailableException.class)
            .hasNoCause()
            .hasMessageNotContaining("isolated secret SQL detail");
        when(replays.replay(10, COMMAND)).thenThrow(new ResourceNotFoundException("isolated missing"));
        assertThatThrownBy(() -> facade(true).replay("10", "session", COMMAND, "proof")).isInstanceOf(
            OperationsNotFoundException.class
        );
    }

    private OutboxOperationsFacade facade(boolean enabled) {
        return new OutboxOperationsFacade("community", enabled, reads, replays, authority);
    }
}
