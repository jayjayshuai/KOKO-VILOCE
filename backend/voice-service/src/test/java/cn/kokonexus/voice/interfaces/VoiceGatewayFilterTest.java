package cn.kokonexus.voice.interfaces;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 执行真实Servlet过滤器，不以业务mock证明数据库、Redis或公网链路。 */
class VoiceGatewayFilterTest {

    /** 非生产测试密钥。 */
    private static final String KEY = "isolated-voice-gateway-test-key-20261005";
    /** 真实过滤器，响应令牌缓存策略与头解析均执行。 */
    private final VoiceGatewayFilter filter = new VoiceGatewayFilter(KEY);

    @Test
    void missingBlankShortWhitespaceOrOversizedKeyFailsStartup() {
        for (String key : new String[] {
            null,
            "",
            " ".repeat(32),
            "x".repeat(31),
            "x".repeat(257),
            "x".repeat(31) + "\n",
        }) {
            assertThrows(IllegalStateException.class, () -> new VoiceGatewayFilter(key));
        }
    }

    @Test
    void forgedIdentityWithoutKeyCannotCreateJoinOrClose() throws Exception {
        for (String path : List.of("/api/voice/rooms", "/api/voice/rooms/1/join", "/api/voice/rooms/1")) {
            var request = request("POST", path);
            request.addHeader("X-Koko-User-Id", "42");
            assertRejected(request);
        }
    }

    @Test
    void wrongDuplicateOrCommaJoinedKeysNeverReachBusiness() throws Exception {
        for (String key : List.of("forged", KEY + ", " + KEY, "x".repeat(1024))) {
            var request = trusted("POST", "/api/voice/rooms/1/join", "42");
            request.removeHeader("X-Koko-Gateway-Key");
            request.addHeader("X-Koko-Gateway-Key", key);
            assertRejected(request);
        }
        var duplicate = trusted("POST", "/api/voice/rooms/1/join", "42");
        duplicate.addHeader("X-Koko-Gateway-Key", KEY);
        assertRejected(duplicate);
    }

    @Test
    void canonicalLongIdentityIsRequiredForPrivateRoutesAndDocumentation() throws Exception {
        for (String user : List.of("0", "-1", "042", "+42", " 42", "42,43", "9223372036854775808", "1e3")) {
            assertRejected(trusted("POST", "/api/voice/rooms/1/join", user));
        }
        assertRejected(trusted("GET", "/v3/api-docs", null));
        var duplicate = trusted("POST", "/api/voice/rooms/1/join", "42");
        duplicate.addHeader("X-Koko-User-Id", "43");
        assertRejected(duplicate);
    }

    @Test
    void discoveryNeedsKeyButNotLoginAndOnlyExactGetIsAnonymous() throws Exception {
        assertAccepted(trusted("GET", "/api/voice/rooms/discovery", null));
        assertRejected(request("GET", "/api/voice/rooms/discovery"));
        assertRejected(trusted("POST", "/api/voice/rooms/discovery", null));
        assertRejected(trusted("GET", "/api/voice/rooms/discovery/", null));
        assertRejected(trusted("GET", "/api/voice/rooms/discovery;alias", null));
    }

    @Test
    void trustedUserCanReachPrivateRouteAndDocumentationWithoutCaching() throws Exception {
        assertAccepted(trusted("POST", "/api/voice/rooms/1/join", "9223372036854775807"));
        assertAccepted(trusted("GET", "/v3/api-docs", "42"));
    }

    @Test
    void onlyExactInternalHealthAndMetricsSkipTrustBoundary() throws Exception {
        for (String path : List.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/prometheus"
        )) {
            var request = request("GET", path);
            var response = new MockHttpServletResponse();
            var passed = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> passed.set(true));
            assertTrue(passed.get());
        }
        for (String path : List.of(
            "/actuator/env",
            "/actuator/health/other",
            "/API/VOICE/rooms",
            "/api/voice;alias/rooms",
            "/error"
        )) {
            assertRejected(request("POST", path));
        }
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> passed.set(true));
        assertFalse(passed.get());
        assertEquals(403, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(response.getContentAsString().contains(KEY));
    }

    private void assertAccepted(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> passed.set(true));
        assertTrue(passed.get());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static MockHttpServletRequest trusted(String method, String path, String user) {
        var request = request(method, path);
        request.addHeader("X-Koko-Gateway-Key", KEY);
        if (user != null) request.addHeader("X-Koko-User-Id", user);
        return request;
    }
}
