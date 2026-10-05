package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.identity.domain.ReplayConfirmationEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 单元分支不证明 MySQL 锁/独立事务；真实代理与 SQL 另行隔离验收。 */
class OperationsAuthorityServiceTest {

    /** 合法但仅测试使用的命令。 */
    private static final OutboxReplayCommand COMMAND = new OutboxReplayCommand(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        0,
        "隔离确认原因不少于十个字符"
    );
    /** 虚拟本人密码，不是线上账号密码。 */
    private static final String PASSWORD = "isolated-test-password";
    /** 降低测试生成成本，不改变生产 BCrypt 12 的配置。 */
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    /** 会话摘要夹具，禁止传入原登录令牌。 */
    private static final String SESSION = ReplayCommandBinding.sha256("fixture-session");
    /** SQL 边界 mock。 */
    private final OperationsAuthorityMapper mapper = mock(OperationsAuthorityMapper.class);
    /** 独立 Bean 调用边界 mock。 */
    private final OperationsCredentialLimiter limiter = mock(OperationsCredentialLimiter.class);
    /** 默认开启仅限本测试，不使用生产配置。 */
    private final OperationsAuthorityService service = new OperationsAuthorityService(true, mapper, limiter);

    @Test
    void disabledAccessIsEmptyAndWritesFailClosedWithoutDatabase() {
        var disabled = new OperationsAuthorityService(false, mapper, limiter);
        assertThat(disabled.access("10").enabled()).isFalse();
        assertThat(disabled.access("10").permissions()).isEmpty();
        assertThatThrownBy(() -> disabled.requirePermission("10", "notification:outbox:read")).isInstanceOf(
            OperationsUnavailableException.class
        );
        assertThatThrownBy(() -> disabled.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD)).isInstanceOf(
            OperationsUnavailableException.class
        );
        disabled.cleanExpiredConfirmations();
        verifyNoInteractions(mapper, limiter);
    }

    @Test
    void missingInactiveAndPermissionlessUsersCannotConfirm() {
        assertThatThrownBy(() -> service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        UserAccount inactive = user(0);
        inactive.setStatus("DISABLED");
        when(mapper.activeUser(10)).thenReturn(inactive);
        assertThatThrownBy(() -> service.access("10")).isInstanceOf(OperationsAccessDeniedException.class);
        when(mapper.activeUser(10)).thenReturn(user(0));
        when(mapper.permissions(10)).thenReturn(List.of("notification:outbox:read"));
        assertThatThrownBy(() -> service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verifyNoInteractions(limiter);
        verify(mapper, never()).insertConfirmation(any());
    }

    @Test
    void wrongPasswordConsumesBudgetButCannotIssueProof() {
        allow();
        assertThatThrownBy(() -> service.confirmReplay("10", SESSION, "identity", COMMAND, "wrong")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verify(limiter).consume(10);
        verify(mapper, never()).insertConfirmation(any());
    }

    @Test
    void invalidCommandAndSessionAreRejectedBeforePasswordBudget() {
        assertThatThrownBy(() -> service.confirmReplay("10", SESSION, "live;DROP", COMMAND, PASSWORD)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> service.confirmReplay("10", "raw-session", "live", COMMAND, PASSWORD)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> service.requirePermission("10", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper, limiter);
    }

    @Test
    void issuingProofStoresOnlyHashesWithDatabaseExpiry() {
        allow();
        var stored = storage();
        var confirmation = service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD);
        var entity = stored.get();
        assertThat(confirmation.confirmationToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(entity.getTokenHash()).isEqualTo(ReplayCommandBinding.sha256(confirmation.confirmationToken()));
        assertThat(entity.getTokenHash()).isNotEqualTo(confirmation.confirmationToken());
        assertThat(entity.getCommandHash()).isEqualTo(ReplayCommandBinding.fingerprint(10, "identity", COMMAND));
        assertThat(entity.getSessionHash()).isEqualTo(SESSION);
        assertThat(entity.getCredentialFingerprint()).isEqualTo(ReplayCommandBinding.sha256(HASH));
        assertThat(confirmation.expiresAt()).isEqualTo(entity.getExpiresAt());
        assertThat(confirmation.toString()).doesNotContain(confirmation.confirmationToken());
        service.validateReplayConfirmation("10", SESSION, "identity", COMMAND, confirmation.confirmationToken());
    }

    @Test
    void proofCannotMoveBetweenSessionDomainCommandActorOrCredentialVersions() {
        allow();
        var stored = storage();
        var confirmation = service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD);
        String token = confirmation.confirmationToken();
        assertDenied(() -> service.validateReplayConfirmation("10", SESSION, "community", COMMAND, token));
        assertDenied(() -> service.validateReplayConfirmation("10", "0".repeat(64), "identity", COMMAND, token));
        var other = new OutboxReplayCommand(COMMAND.requestId(), COMMAND.eventId(), 1, COMMAND.reason());
        assertDenied(() -> service.validateReplayConfirmation("10", SESSION, "identity", other, token));
        when(mapper.activeUser(11)).thenReturn(user(0));
        when(mapper.permissions(11)).thenReturn(List.of("notification:outbox:replay"));
        assertDenied(() -> service.validateReplayConfirmation("11", SESSION, "identity", COMMAND, token));
        when(mapper.activeUser(10)).thenReturn(user(1));
        assertDenied(() -> service.validateReplayConfirmation("10", SESSION, "identity", COMMAND, token));
        var changedPassword = user(0);
        changedPassword.setPasswordHash(new BCryptPasswordEncoder(4).encode("changed-test-password"));
        when(mapper.activeUser(10)).thenReturn(changedPassword);
        assertDenied(() -> service.validateReplayConfirmation("10", SESSION, "identity", COMMAND, token));
        when(mapper.activeUser(10)).thenReturn(user(0));
        stored.set(null); // Mapper 只返回数据库中尚未过期的行。
        assertDenied(() -> service.validateReplayConfirmation("10", SESSION, "identity", COMMAND, token));
    }

    @Test
    void midConfirmationRevocationAndPasswordChangeCannotUseEarlierSnapshot() {
        allow();
        when(mapper.permissions(10)).thenReturn(List.of("notification:outbox:replay"), List.of());
        assertDenied(() -> service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD));
        when(mapper.permissions(10)).thenReturn(List.of("notification:outbox:replay"));
        when(mapper.activeUser(10)).thenReturn(user(0), user(1));
        assertDenied(() -> service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD));
        var changedPassword = user(0);
        changedPassword.setPasswordHash(new BCryptPasswordEncoder(4).encode("changed-test-password"));
        when(mapper.activeUser(10)).thenReturn(user(0), changedPassword);
        assertDenied(() -> service.confirmReplay("10", SESSION, "identity", COMMAND, PASSWORD));
        verify(mapper, never()).insertConfirmation(any());
    }

    @Test
    void databaseFaultsPropagateAndCleanupIsBounded() {
        when(mapper.activeUser(10)).thenThrow(new IllegalStateException("isolated DB offline"));
        assertThatThrownBy(() -> service.access("10")).isInstanceOf(IllegalStateException.class);
        service.cleanExpiredConfirmations();
        verify(mapper).deleteExpiredConfirmations(500);
    }

    private void allow() {
        when(mapper.activeUser(10)).thenReturn(user(0));
        when(mapper.roles(10)).thenReturn(List.of("NOTIFICATION_OPERATOR"));
        when(mapper.permissions(10)).thenReturn(List.of("notification:outbox:read", "notification:outbox:replay"));
    }

    private AtomicReference<ReplayConfirmationEntity> storage() {
        var stored = new AtomicReference<ReplayConfirmationEntity>();
        when(mapper.insertConfirmation(any())).thenAnswer(invocation -> {
            ReplayConfirmationEntity entity = invocation.getArgument(0);
            entity.setExpiresAt(LocalDateTime.of(2026, 10, 3, 22, 0));
            stored.set(entity);
            return 1;
        });
        when(mapper.findConfirmation(anyString())).thenAnswer(invocation -> stored.get());
        return stored;
    }

    private static UserAccount user(long version) {
        var user = new UserAccount();
        user.setId(10L);
        user.setStatus("ACTIVE");
        user.setOperationsVersion(version);
        user.setPasswordHash(HASH);
        return user;
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(OperationsAccessDeniedException.class);
    }
}
