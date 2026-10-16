package cn.kokonexus.chat.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Cookie 名必须与配置一致且不可注入分隔符；不接触真实 Redis。 */
class ChatSecurityTest {

    private static final String KEY = "test-only-internal-key-01234567890123456789";

    @Test
    void configuredNameAndDefaultRemainExact() {
        assertEquals(
            "koko-custom-session",
            new ChatSecurity(KEY, "http://127.0.0.1", "koko-custom-session").tokenName()
        );
        assertEquals("koko-nexus-token", new ChatSecurity(KEY, "http://127.0.0.1").tokenName());
    }

    @Test
    void missingInvalidOrOversizedCookieNameFailsAtStartup() {
        for (String name : new String[] {
            null,
            "",
            "name=value",
            "name;other",
            "name\r\nHeader",
            "has space",
            "a".repeat(129),
        }) {
            assertThrows(IllegalStateException.class, () -> new ChatSecurity(KEY, "http://127.0.0.1", name));
        }
    }
}
