package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;

/** 网站会话读取适配，不用登录标记或缓存替代当前token核验；实际Redis仍需全网联调。 */
class MediaWebsiteSessionCheckTest {

    @Test
    void currentSessionMustMatchOwnerAndInvalidTokensDoNotReadDao() {
        var service = new MediaWebsiteSessionCheck();
        try (var library = mockStatic(StpUtil.class)) {
            assertThat(service.active(null, "42")).isFalse();
            assertThat(service.active("x".repeat(8193), "42")).isFalse();
            library.verifyNoInteractions();
            library.when(() -> StpUtil.getLoginIdByToken("synthetic-cookie")).thenReturn("42");
            assertThat(service.active("synthetic-cookie", "42")).isTrue();
            assertThat(service.active("synthetic-cookie", "43")).isFalse();
            library.when(() -> StpUtil.getLoginIdByToken("synthetic-cookie")).thenReturn(null);
            assertThat(service.active("synthetic-cookie", "42")).isFalse();
        }
    }
}
