package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.identity.domain.OperationsBootstrapApproval;
import cn.kokonexus.identity.domain.OperationsRoleAudit;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.OperationsBootstrapMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsRoleMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 业务边界测试不代替实际 SQL 的 guard 并发与审批失败回滚。 */
class OperationsBootstrapServiceTest {

    /** 持久化边界桩，不赋权真实账号。 */
    private final OperationsRoleMapper roles = mock(OperationsRoleMapper.class);
    /** 数据库实例和审批事实边界桩。 */
    private final OperationsBootstrapMapper bootstrap = mock(OperationsBootstrapMapper.class);
    /** 不注册为应用启动 Bean 的生产用例。 */
    private final OperationsBootstrapService service = new OperationsBootstrapService(roles, bootstrap);
    /** 合成账号。 */
    private UserAccount user;
    /** 合成审批命令，不使用实际运营账号。 */
    private final OperationsBootstrapCommand command = new OperationsBootstrapCommand(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        80,
        "isolated_80",
        LocalDateTime.of(2026, 10, 4, 0, 0),
        "负责人隔离验收审批原因不少于十字"
    );

    @BeforeEach
    void setup() {
        when(bootstrap.databaseName()).thenReturn("koko_identity");
        when(bootstrap.databaseServerId()).thenReturn("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        when(bootstrap.validExpiry(any())).thenReturn(1);
        when(roles.enabledRole("OPERATIONS_ADMIN")).thenReturn(1);
        when(roles.lockGuard()).thenReturn(1);
        user = new UserAccount();
        user.setId(80L);
        user.setStatus("ACTIVE");
        user.setHandle("isolated_80");
        user.setOperationsVersion(0L);
        when(bootstrap.inspectUser(80)).thenReturn(user);
        when(roles.lockUser(80)).thenReturn(user);
    }

    @Test
    void planDoesNotMutateAndApprovalIsMandatoryBeforeGuard() {
        assertThat(service.plan("koko_identity", command)).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> service.apply("koko_identity", command, null)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verify(roles, never()).lockGuard();
        verify(roles, never()).insertAssignment(any());
        verify(bootstrap, never()).insertApproval(any());
    }

    @Test
    void firstApprovalWritesRoleAccountAuditAndBinding() {
        String hash = service.plan("koko_identity", command);
        when(roles.insertAssignment(any())).thenReturn(1);
        when(roles.advanceAuthorityVersion(80, 0)).thenReturn(1);
        when(roles.insertAudit(any())).thenReturn(1);
        when(bootstrap.insertApproval(any())).thenReturn(1);
        when(roles.findAudit(command.requestId())).thenReturn(null, audit());
        var receipt = service.apply("koko_identity", command, hash);
        assertThat(receipt.version()).isEqualTo(1);
        verify(roles).advanceAuthorityVersion(80, 0);
        verify(bootstrap).insertApproval(any());
    }

    @Test
    void historyIncludingRevokedRolesPreventsAnotherBootstrap() {
        String hash = service.plan("koko_identity", command);
        when(bootstrap.hasAuthorityHistory()).thenReturn(1);
        assertThatThrownBy(() -> service.apply("koko_identity", command, hash))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("历史");
        verify(roles, never()).insertAssignment(any());
    }

    @Test
    void wrongHandleInactiveAccountExpiryOrDisabledRoleCannotBePlanned() {
        user.setHandle("not-selected");
        assertThatThrownBy(() -> service.plan("koko_identity", command)).isInstanceOf(IllegalArgumentException.class);
        user.setHandle("isolated_80");
        user.setStatus("DISABLED");
        assertThatThrownBy(() -> service.plan("koko_identity", command)).isInstanceOf(IllegalArgumentException.class);
        user.setStatus("ACTIVE");
        when(bootstrap.validExpiry(any())).thenReturn(0);
        assertThatThrownBy(() -> service.plan("koko_identity", command)).isInstanceOf(IllegalArgumentException.class);
        when(bootstrap.validExpiry(any())).thenReturn(1);
        when(roles.enabledRole("OPERATIONS_ADMIN")).thenReturn(0);
        assertThatThrownBy(() -> service.plan("koko_identity", command)).isInstanceOf(IllegalArgumentException.class);
        verify(roles, never()).insertAssignment(any());
    }

    @Test
    void originalReceiptRemainsHistoricalAfterAccountOrRoleChanges() {
        String hash = service.plan("koko_identity", command);
        var bound = new OperationsBootstrapApproval();
        bound.setRequestId(command.requestId());
        bound.setCommandHash(hash);
        bound.setApprovedHandle("isolated_80");
        when(roles.findAudit(command.requestId())).thenReturn(audit());
        when(bootstrap.findApproval(command.requestId())).thenReturn(bound);
        user.setStatus("DISABLED");
        user.setHandle("renamed");
        assertThat(service.apply("koko_identity", command, hash).version()).isEqualTo(1);
        verify(roles, never()).lockUser(anyLong());
        verify(roles, never()).advanceAuthorityVersion(anyLong(), anyLong());
    }

    @Test
    void changedDatabaseInstanceOrFullCommandInvalidatesOldApproval() {
        String hash = service.plan("koko_identity", command);
        when(bootstrap.databaseServerId()).thenReturn("cccccccc-cccc-cccc-cccc-cccccccccccc");
        assertThatThrownBy(() -> service.apply("koko_identity", command, hash)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        assertThatThrownBy(() -> service.plan("wrong_database", command)).isInstanceOf(IllegalArgumentException.class);
        when(bootstrap.databaseServerId()).thenReturn("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        var changed = new OperationsBootstrapCommand(
            command.requestId(),
            80,
            "isolated_80",
            command.expiresAt(),
            "已改变的审批原因仍不少于十字"
        );
        assertThatThrownBy(() -> service.apply("koko_identity", changed, hash)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verify(roles, never()).lockGuard();
    }

    @Test
    void overflowOrApprovalInsertFailureCannotProduceAcceptedFact() {
        String hash = service.plan("koko_identity", command);
        user.setOperationsVersion(Long.MAX_VALUE);
        assertThatThrownBy(() -> service.apply("koko_identity", command, hash)).isInstanceOf(
            IllegalStateException.class
        );
        verify(roles, never()).insertAssignment(any());
        user.setOperationsVersion(0L);
        when(roles.insertAssignment(any())).thenReturn(1);
        when(roles.advanceAuthorityVersion(80, 0)).thenReturn(1);
        when(roles.insertAudit(any())).thenReturn(1);
        when(bootstrap.insertApproval(any())).thenReturn(0);
        assertThatThrownBy(() -> service.apply("koko_identity", command, hash))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("审批绑定");
    }

    @Test
    void nonMicrosecondNullExpiryAndMissingInputRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.plan("koko_identity", null)).isInstanceOf(IllegalArgumentException.class);
        for (LocalDateTime expiry : new LocalDateTime[] { null, command.expiresAt().withNano(1) }) {
            assertThatThrownBy(() ->
                service.plan(
                    "koko_identity",
                    new OperationsBootstrapCommand(command.requestId(), 80, "isolated_80", expiry, command.reason())
                )
            ).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(command.toString()).doesNotContain(command.reason());
        verify(bootstrap, never()).databaseName();
    }

    private OperationsRoleAudit audit() {
        var audit = new OperationsRoleAudit();
        audit.setRequestId(command.requestId());
        audit.setSource("SERVER_BOOTSTRAP");
        audit.setUserId(80L);
        audit.setRoleCode("OPERATIONS_ADMIN");
        audit.setExpectedVersion(0L);
        audit.setAcceptedVersion(1L);
        audit.setPreviousStatus("NONE");
        audit.setAcceptedStatus("ACTIVE");
        audit.setExpiresAt(command.expiresAt());
        audit.setReason(command.reason());
        audit.setCreatedAt(LocalDateTime.of(2026, 10, 3, 23, 0));
        return audit;
    }
}
