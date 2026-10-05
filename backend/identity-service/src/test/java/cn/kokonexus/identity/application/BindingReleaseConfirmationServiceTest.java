package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.identity.domain.ReplayConfirmationEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.BindingReleaseConfirmationMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 专用确认分支测试；密码限速/SQL时钟/事务仍需实际数据库验收。 */
class BindingReleaseConfirmationServiceTest {

    private final OperationsAuthorityService authority = mock(OperationsAuthorityService.class);
    private final OperationsAuthorityMapper users = mock(OperationsAuthorityMapper.class);
    private final BindingReleaseConfirmationMapper mapper = mock(BindingReleaseConfirmationMapper.class);
    private final BindingReleaseReplayCommand command = new BindingReleaseReplayCommand(
        "81000000-0000-4000-8000-000000000001",
        "81000000-0000-4000-8000-000000000002",
        0,
        "工单根因排查并已处理后恢复"
    );
    private final String session = "a".repeat(64);

    @Test
    void allFeatureSwitchesRequiredAndNoTableAccessWhenClosed() {
        for (int disabled = 0; disabled < 3; disabled++) {
            var service = new BindingReleaseConfirmationService(
                disabled != 0,
                disabled != 1,
                disabled != 2,
                authority,
                users,
                mapper
            );
            assertThatThrownBy(() -> service.confirm("10", session, "identity", command, "password")).isInstanceOf(
                OperationsUnavailableException.class
            );
            service.cleanExpired();
        }
        verifyNoInteractions(mapper, users, authority);
    }

    @Test
    void onlySecretHashSavedAndAllBoundFieldsVersionsChecked() {
        var user = new UserAccount();
        user.setStatus("ACTIVE");
        user.setPasswordHash("opaque-bcrypt-hash");
        user.setOperationsVersion(1L);
        var saved = new AtomicReference<ReplayConfirmationEntity>();
        when(authority.confirmBindingRecoveryCredential("10", "password")).thenReturn(user);
        when(mapper.insert(any())).thenAnswer(call -> {
            var entity = call.<ReplayConfirmationEntity>getArgument(0);
            entity.setExpiresAt(LocalDateTime.of(2026, 10, 5, 1, 0));
            saved.set(entity);
            return 1;
        });
        when(mapper.find(anyString())).thenAnswer(call -> saved.get());
        when(users.activeUser(10)).thenReturn(user);
        var service = new BindingReleaseConfirmationService(true, true, true, authority, users, mapper);
        var proof = service.confirm("10", session, "identity", command, "password");
        assertThat(proof.confirmationToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(saved.get().getTokenHash())
            .isEqualTo(ReplayCommandBinding.sha256(proof.confirmationToken()))
            .isNotEqualTo(proof.confirmationToken());
        assertThat(proof.toString()).doesNotContain(proof.confirmationToken());
        service.validate("10", session, "identity", command, proof.confirmationToken());
        assertThatThrownBy(() ->
            service.validate("10", "b".repeat(64), "identity", command, proof.confirmationToken())
        ).isInstanceOf(OperationsAccessDeniedException.class);
        assertThatThrownBy(() ->
            service.validate("10", session, "community", command, proof.confirmationToken())
        ).isInstanceOf(OperationsAccessDeniedException.class);
        user.setOperationsVersion(2L);
        assertThatThrownBy(() ->
            service.validate("10", session, "identity", command, proof.confirmationToken())
        ).isInstanceOf(OperationsAccessDeniedException.class);
    }

    @Test
    void notificationOrExpiredTokenNotFoundInDedicatedTableCannotAuthorize() {
        var user = new UserAccount();
        user.setStatus("ACTIVE");
        when(users.activeUser(10)).thenReturn(user);
        var service = new BindingReleaseConfirmationService(true, true, true, authority, users, mapper);
        assertThatThrownBy(() -> service.validate("10", session, "identity", command, "b".repeat(43))).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        verify(authority).requirePermission("10", "asset:binding:replay");
        verify(mapper).find(ReplayCommandBinding.sha256("b".repeat(43)));
    }

    @Test
    void malformedCommandsDoNotSpendPasswordBudgetOrWriteSecrets() {
        var service = new BindingReleaseConfirmationService(true, true, true, authority, users, mapper);
        assertThatThrownBy(() -> service.confirm("10", session, "live", command, "password")).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(authority, users, mapper);
    }
}
