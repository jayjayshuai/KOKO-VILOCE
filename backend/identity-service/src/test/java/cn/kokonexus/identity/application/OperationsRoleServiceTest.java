package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.identity.domain.OperationsRoleAssignment;
import cn.kokonexus.identity.domain.OperationsRoleAudit;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsRoleMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** Mock 验证命令分支与受影响行数；审计失败回滚由真实 SQL/事务另外证明。 */
class OperationsRoleServiceTest {

    /** 测试幂等请求，不指向线上审计。 */
    private static final String REQUEST = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    /** 测试原因，不包含生产工单。 */
    private static final String REASON = "隔离管理员明确授权测试原因";
    /** 原子关系及审计 SQL 边界。 */
    private final OperationsRoleMapper mapper = mock(OperationsRoleMapper.class);
    /** 本人二次确认与事实授权边界。 */
    private final OperationsAuthorityService authority = mock(OperationsAuthorityService.class);
    /** guard 后重新读账号事实。 */
    private final OperationsAuthorityMapper credentials = mock(OperationsAuthorityMapper.class);
    /** 角色用例，单元测试不启用事务代理。 */
    private final OperationsRoleService service = new OperationsRoleService(mapper, authority, credentials);

    @Test
    void selfChangeAndSubmicrosecondExpiryFailBeforePasswordOrSql() {
        var self = new OperationsRoleChangeCommand(REQUEST, "10", "OPERATIONS_ADMIN", true, 0, null, REASON);
        assertThatThrownBy(() -> service.changeRole("10", self, "fixture")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        var imprecise = new OperationsRoleChangeCommand(
            REQUEST,
            "20",
            "NOTIFICATION_OPERATOR",
            true,
            0,
            LocalDateTime.of(2026, 10, 4, 0, 0, 0, 1),
            REASON
        );
        assertThatThrownBy(() -> service.changeRole("10", imprecise, "fixture")).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(mapper, authority, credentials);
    }

    @Test
    void freshGrantUpdatesBothVersionsAndAppendsAudit() {
        allow();
        var audit = audit();
        when(mapper.findAudit(REQUEST)).thenReturn(null, audit);
        when(mapper.insertAssignment(any())).thenReturn(1);
        when(mapper.advanceAuthorityVersion(20, 4)).thenReturn(1);
        when(mapper.insertAudit(any())).thenReturn(1);
        var receipt = service.changeRole("10", command(true, 0), "fixture");
        assertThat(receipt.version()).isEqualTo(1);
        assertThat(receipt.status()).isEqualTo("ACTIVE");
        var captured = ArgumentCaptor.forClass(OperationsRoleAudit.class);
        verify(mapper).insertAudit(captured.capture());
        assertThat(captured.getValue().getPreviousStatus()).isEqualTo("NONE");
        assertThat(captured.getValue().getOperatorId()).isEqualTo(10);
        assertThat(captured.getValue().getSource()).isEqualTo("OPERATOR");
        verify(authority).requirePermission("10", "operations:roles:manage");
    }

    @Test
    void acceptedCommandReturnsHistoryEvenIfTargetLaterInactive() {
        allow();
        var target = user(20, 4);
        target.setStatus("DISABLED");
        when(mapper.lockUser(20)).thenReturn(target);
        when(mapper.findAudit(REQUEST)).thenReturn(audit());
        assertThat(service.changeRole("10", command(true, 0), "fixture").version()).isEqualTo(1);
        verify(mapper, never()).insertAssignment(any());
        verify(mapper, never()).insertAudit(any());
    }

    @Test
    void sameRequestWithDifferentCommandIsConflictNotRegrant() {
        allow();
        when(mapper.findAudit(REQUEST)).thenReturn(audit());
        assertThatThrownBy(() -> service.changeRole("10", command(false, 0), "fixture")).isInstanceOf(
            IllegalStateException.class
        );
        verify(mapper, never()).insertAssignment(any());
    }

    @Test
    void guardAcquisitionCannotKeepRevokedManagerAuthority() {
        allow();
        doThrow(new OperationsAccessDeniedException("隔离撤权"))
            .when(authority)
            .requirePermission("10", "operations:roles:manage");
        assertThatThrownBy(() -> service.changeRole("10", command(true, 0), "fixture")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verify(mapper, never()).lockUser(20);
    }

    @Test
    void guardAcquisitionCannotKeepOldPasswordOrEpoch() {
        allow();
        when(credentials.activeUser(10)).thenReturn(user(10, 1));
        assertThatThrownBy(() -> service.changeRole("10", command(true, 0), "fixture")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        var changed = user(10, 0);
        changed.setPasswordHash("different-fixture-hash");
        when(credentials.activeUser(10)).thenReturn(changed);
        assertThatThrownBy(() -> service.changeRole("10", command(true, 0), "fixture")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verify(mapper, never()).lockUser(20);
    }

    @Test
    void staleVersionAndMissingRevokeCannotMutate() {
        allow();
        assertThatThrownBy(() -> service.changeRole("10", command(true, 3), "fixture")).isInstanceOf(
            IllegalStateException.class
        );
        assertThatThrownBy(() -> service.changeRole("10", command(false, 0), "fixture")).isInstanceOf(
            IllegalStateException.class
        );
        verify(mapper, never()).insertAssignment(any());
    }

    @Test
    void expiredGrantAndInactiveTargetCannotMutate() {
        allow();
        var expired = new OperationsRoleChangeCommand(
            REQUEST,
            "20",
            "NOTIFICATION_OPERATOR",
            true,
            0,
            LocalDateTime.of(2025, 1, 1, 0, 0),
            REASON
        );
        assertThatThrownBy(() -> service.changeRole("10", expired, "fixture")).isInstanceOf(
            IllegalArgumentException.class
        );
        var inactive = user(20, 4);
        inactive.setStatus("DISABLED");
        when(mapper.lockUser(20)).thenReturn(inactive);
        assertThatThrownBy(() -> service.changeRole("10", command(true, 0), "fixture")).isInstanceOf(
            IllegalArgumentException.class
        );
        verify(mapper, never()).insertAssignment(any());
    }

    @Test
    void revokeKeepsRelationAndAuditPreviousExpiry() {
        allow();
        var previous = new OperationsRoleAssignment();
        previous.setUserId(20L);
        previous.setRoleCode("NOTIFICATION_OPERATOR");
        previous.setStatus("ACTIVE");
        previous.setVersion(1L);
        previous.setExpiresAt(LocalDateTime.of(2026, 11, 1, 0, 0));
        when(mapper.findAssignment(20, "NOTIFICATION_OPERATOR")).thenReturn(previous);
        when(mapper.updateAssignment(any(), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(1);
        when(mapper.advanceAuthorityVersion(20, 4)).thenReturn(1);
        when(mapper.insertAudit(any())).thenReturn(1);
        var saved = audit();
        saved.setAcceptedStatus("REVOKED");
        saved.setExpectedVersion(1L);
        saved.setAcceptedVersion(2L);
        when(mapper.findAudit(REQUEST)).thenReturn(null, saved);
        assertThat(service.changeRole("10", command(false, 1), "fixture").version()).isEqualTo(2);
        var captured = ArgumentCaptor.forClass(OperationsRoleAudit.class);
        verify(mapper).insertAudit(captured.capture());
        assertThat(captured.getValue().getPreviousExpiresAt()).isEqualTo(previous.getExpiresAt());
        assertThat(captured.getValue().getAcceptedStatus()).isEqualTo("REVOKED");
        assertThat(captured.getValue().getExpiresAt()).isNull();
        verify(mapper, never()).insertAssignment(any());
    }

    @Test
    void failedEpochCasAndAuditCollisionAreNotSuccess() {
        allow();
        when(mapper.insertAssignment(any())).thenReturn(1);
        assertThatThrownBy(() -> service.changeRole("10", command(true, 0), "fixture")).isInstanceOf(
            IllegalStateException.class
        );
        verify(mapper, never()).insertAudit(any());
        when(mapper.advanceAuthorityVersion(20, 4)).thenReturn(1);
        when(mapper.insertAudit(any())).thenThrow(new DuplicateKeyException("isolated collision"));
        assertThatThrownBy(() -> service.changeRole("10", command(true, 0), "fixture")).isInstanceOf(
            IllegalStateException.class
        );
    }

    private void allow() {
        when(authority.confirmRoleManager("10", "fixture")).thenReturn(user(10, 0));
        when(credentials.activeUser(10)).thenReturn(user(10, 0));
        when(mapper.lockGuard()).thenReturn(1);
        when(mapper.lockUser(20)).thenReturn(user(20, 4));
        when(mapper.enabledRole("NOTIFICATION_OPERATOR")).thenReturn(1);
    }

    private static UserAccount user(long id, long version) {
        var user = new UserAccount();
        user.setId(id);
        user.setStatus("ACTIVE");
        user.setPasswordHash("fixture-hash-not-a-password");
        user.setOperationsVersion(version);
        return user;
    }

    private static OperationsRoleChangeCommand command(boolean enabled, long version) {
        return new OperationsRoleChangeCommand(REQUEST, "20", "NOTIFICATION_OPERATOR", enabled, version, null, REASON);
    }

    private static OperationsRoleAudit audit() {
        var audit = new OperationsRoleAudit();
        audit.setRequestId(REQUEST);
        audit.setOperatorId(10L);
        audit.setSource("OPERATOR");
        audit.setUserId(20L);
        audit.setRoleCode("NOTIFICATION_OPERATOR");
        audit.setExpectedVersion(0L);
        audit.setAcceptedVersion(1L);
        audit.setPreviousStatus("NONE");
        audit.setAcceptedStatus("ACTIVE");
        audit.setReason(REASON);
        audit.setCreatedAt(LocalDateTime.of(2026, 10, 3, 21, 0));
        return audit;
    }
}
