package cn.kokonexus.chat.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 审核权限默认拒绝，不能由注册次序、空配置或其他 loginType 获得。 */
class ChatPermissionProviderTest {

    @Test
    void noImplicitModeratorWhenUnconfigured() {
        var provider = new ChatPermissionProvider("");
        assertTrue(provider.getPermissionList("1", "login").isEmpty());
        assertTrue(provider.getRoleList("1", "login").isEmpty());
        assertTrue(provider.getPermissionList(null, "login").isEmpty());
    }

    @Test
    void configuredIdentityAndLoginTypeAreBothRequired() {
        var provider = new ChatPermissionProvider("42, 43");
        assertEquals(
            java.util.List.of(ChatPermissionProvider.READ, ChatPermissionProvider.REVIEW),
            provider.getPermissionList(42L, "login")
        );
        assertEquals(java.util.List.of("CHAT_MODERATOR"), provider.getRoleList("43", "login"));
        assertTrue(provider.getPermissionList("44", "login").isEmpty());
        assertTrue(provider.getPermissionList("42", "admin").isEmpty());
    }

    @Test
    void malformedConfigurationFailsStartupInsteadOfBroadeningAccess() {
        for (String value : java.util.List.of("*", "0", "-1", "42,", "42,x", "9223372036854775808")) {
            assertThrows(IllegalArgumentException.class, () -> new ChatPermissionProvider(value));
        }
    }
}
