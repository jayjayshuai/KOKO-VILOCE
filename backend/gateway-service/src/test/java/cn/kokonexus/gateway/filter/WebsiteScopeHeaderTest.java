package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;

import cn.kokonexus.api.voice.WebsiteSessionScope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 身份头边界的真实过滤器测试；Sa-Token属性明确为桩，公网会话另外实测。 */
class WebsiteScopeHeaderTest {

    @Test
    void clientScopeIsRemovedAndOnlyAuthenticatedCredentialRouteGetsServerScope() {
        var filter = new TrustedUserHeaderFilter("synthetic-key");
        String scope = WebsiteSessionScope.fromToken("synthetic-cookie");
        for (String path : new String[] {
            "/api/voice/rooms/9/interaction/media-credentials",
            "/api/media/livekit/rtc/v1",
            "/api/voice/rooms/9/interaction",
        }) {
            var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header(TrustedUserHeaderFilter.WEBSITE_SCOPE_HEADER, "forged")
            );
            exchange.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
            exchange.getAttributes().put(TrustedUserHeaderFilter.WEBSITE_SCOPE_ATTRIBUTE, scope);
            filter
                .filter(exchange, next -> {
                    assertThat(
                        next.getRequest().getHeaders().getFirst(TrustedUserHeaderFilter.WEBSITE_SCOPE_HEADER)
                    ).isEqualTo(path.endsWith("/media-credentials") ? scope : null);
                    return Mono.empty();
                })
                .block();
        }
        var anonymous = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/voice/rooms/9/interaction/media-credentials").header(
                TrustedUserHeaderFilter.WEBSITE_SCOPE_HEADER,
                scope
            )
        );
        filter
            .filter(anonymous, next -> {
                assertThat(
                    next.getRequest().getHeaders().containsKey(TrustedUserHeaderFilter.WEBSITE_SCOPE_HEADER)
                ).isFalse();
                return Mono.empty();
            })
            .block();
    }
}
