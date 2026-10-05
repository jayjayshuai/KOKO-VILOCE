package cn.kokonexus.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 实际网关GlobalFilter清除伪造头并仅给固定内部服务注入密钥；不代替真实路由联调。 */
class TrustedVoiceHeaderTest {

    /** 非生产密钥，测试不记录实际服务器秘密。 */
    private static final String KEY = "isolated-voice-gateway-test-key-20261005";

    @Test
    void voiceRoutesAndDocReceiveOnlyServerKeyAndAuthenticatedIdentity() {
        for (String path : List.of("/api/voice/rooms", "/api/voice/rooms/1/join", "/api/docs/voice/v3/api-docs")) {
            var forwarded = forward(path, "42", KEY);
            assertEquals(
                List.of(KEY),
                forwarded.getRequest().getHeaders().get(TrustedUserHeaderFilter.INTERNAL_KEY_HEADER)
            );
            assertEquals(
                List.of("42"),
                forwarded.getRequest().getHeaders().get(TrustedUserHeaderFilter.USER_ID_HEADER)
            );
        }
    }

    @Test
    void anonymousDiscoveryNeverForwardsForgedUser() {
        var forwarded = forward("/api/voice/rooms/discovery", null, KEY);
        assertNull(forwarded.getRequest().getHeaders().getFirst(TrustedUserHeaderFilter.USER_ID_HEADER));
        assertEquals(KEY, forwarded.getRequest().getHeaders().getFirst(TrustedUserHeaderFilter.INTERNAL_KEY_HEADER));
    }

    @Test
    void otherServicesAndMissingConfiguredKeyNeverReceiveClientKey() {
        for (String path : List.of("/api/voice-other/rooms", "/api/communities", "/api/docs/community/v3/api-docs")) {
            assertNull(
                forward(path, "42", KEY).getRequest().getHeaders().getFirst(TrustedUserHeaderFilter.INTERNAL_KEY_HEADER)
            );
        }
        assertNull(
            forward("/api/voice/rooms", "42", "")
                .getRequest()
                .getHeaders()
                .getFirst(TrustedUserHeaderFilter.INTERNAL_KEY_HEADER)
        );
    }

    private static ServerWebExchange forward(String path, String user, String key) {
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(path)
                .header(TrustedUserHeaderFilter.USER_ID_HEADER, "999", "888")
                .header(TrustedUserHeaderFilter.INTERNAL_KEY_HEADER, "forged", "other")
        );
        if (user != null) exchange.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, user);
        var result = new AtomicReference<ServerWebExchange>();
        new TrustedUserHeaderFilter(key)
            .filter(exchange, next -> {
                result.set(next);
                return Mono.empty();
            })
            .block();
        return result.get();
    }
}
