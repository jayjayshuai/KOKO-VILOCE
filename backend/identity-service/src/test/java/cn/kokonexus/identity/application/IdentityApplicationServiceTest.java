package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.identity.AuthenticateIdentityCommand;
import cn.kokonexus.api.identity.RegisterIdentityCommand;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.Test;

class IdentityApplicationServiceTest {

    @Test
    void duplicateRegistrationDoesNotExposeOrSerializeDriverCause() {
        when(userMapper.insert(any(UserAccount.class))).thenThrow(
            new org.springframework.dao.DuplicateKeyException(
                "synthetic-private-email-and-password-hash",
                new java.sql.SQLIntegrityConstraintViolationException("synthetic-driver-detail")
            )
        );
        assertThatThrownBy(() ->
            service.register(
                new RegisterIdentityCommand(
                    "duplicate@example.invalid",
                    "synthetic-password-value",
                    "synthetic_user",
                    "合成用户"
                )
            )
        )
            .isInstanceOf(cn.kokonexus.api.identity.RegistrationConflictException.class)
            .hasMessage("邮箱或用户名已被注册，请登录或使用其他邮箱和用户名")
            .hasNoCause();
        verify(userMapper).insert(any(UserAccount.class));
    }

    private final UserMapper userMapper = mock(UserMapper.class);
    private final IdentityApplicationService service = new IdentityApplicationService(userMapper);

    @Test
    void registerNormalizesIdentityAndHashesPassword() {
        when(userMapper.insert(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            user.setId(1001L);
            return 1;
        });

        var identity = service.register(
            new RegisterIdentityCommand(" OWNER@Example.com ", "a-secure-password", "Creator_01", " 创作者一号 ")
        );

        assertThat(identity.id()).isEqualTo("1001");
        assertThat(identity.email()).isEqualTo("owner@example.com");
        assertThat(identity.handle()).isEqualTo("creator_01");
        assertThat(identity.displayName()).isEqualTo("创作者一号");
        verify(userMapper).insert(any(UserAccount.class));
    }

    @Test
    void authenticateRejectsUnknownAccount() {
        assertThatThrownBy(() ->
            service.authenticate(new AuthenticateIdentityCommand("missing@example.com", "a-secure-password"))
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("邮箱或密码错误");
    }

    @Test
    void registerRejectsWeakPasswordBeforeWriting() {
        assertThatThrownBy(() ->
            service.register(new RegisterIdentityCommand("owner@example.com", "short", "creator_01", "创作者一号"))
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10 到 72");
    }
}
