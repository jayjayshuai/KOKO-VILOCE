package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.gateway.infrastructure.MediaAdmissionClient;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 私人路由不能落到公开房间准入，默认关闭、别名及身份伪造均失败关闭。 */
class NativeCallAdmissionFilterTest {

    private static final String ORIGIN = "http://127.0.0.1:45180";

    private MockServerWebExchange exchange(String path) {
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.GET,
                java.net.URI.create(path + "?access_token=synthetic-token")
            )
                .header("Origin", ORIGIN)
                .header("Upgrade", "websocket")
                .cookie(new org.springframework.http.HttpCookie("koko-nexus-token", "synthetic-cookie"))
        );
        exchange.getAttributes().put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async()
                .id("native-call-signal")
                .uri("ws://127.0.0.1:45181")
                .predicate(ignored -> true)
                .build()
        );
        exchange.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
        return exchange;
    }

    @Test
    void publicFeatureEnabledDoesNotEnablePrivateCalling() {
        var client = mock(MediaAdmissionClient.class);
        try (var gate = new LiveKitAdmissionFilter(client, true, ORIGIN, "koko-nexus-token", 1, 1)) {
            var exchange = exchange("/api/media/native-call/livekit/rtc");
            gate.filter(exchange, ignored -> Mono.error(new AssertionError())).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            verifyNoInteractions(client);
        }
    }

    @Test
    void nativeEntryRecordsDomainProofNeverCallsPublicRpcAndRejectsPathAliases() {
        var client = mock(MediaAdmissionClient.class);
        when(client.admitNative(any())).thenReturn(true);
        try (var gate = new LiveKitAdmissionFilter(client, false, ORIGIN, "koko-nexus-token", 1, 1, true)) {
            var exchange = exchange("/api/media/native-call/livekit/rtc/v1");
            gate.filter(exchange, next -> {
                MediaConnectionProof proof = next.getAttribute(MediaConnectionProof.ATTRIBUTE);
                assertThat(proof.nativeCall()).isTrue();
                assertThat(next.getRequest().getHeaders().containsKey("Cookie")).isFalse();
                return Mono.empty();
            }).block();
            verify(client).admitNative(any());
            verify(client, never()).admit(any());
            clearInvocations(client);
            for (String path : new String[] {
                "/api/media/livekit/rtc",
                "/api/media/native-call/livekit/rtc%2Fv1",
                "/api/media/native-call/livekit//rtc",
                "/api/media/native-call/livekit/rtc/extra",
            }) {
                var bad = exchange(path);
                gate.filter(bad, ignored -> Mono.error(new AssertionError())).block();
                assertThat(bad.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
            var anonymous = exchange("/api/media/native-call/livekit/rtc");
            anonymous.getAttributes().remove(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE);
            gate.filter(anonymous, ignored -> Mono.error(new AssertionError())).block();
            assertThat(anonymous.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(client);
        }
    }

    @Test
    void privacyIsAppliedBeforeLaterAuthenticationOrRateLimitFailure() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat/calls"));
        new NativeCallPrivacyFilter()
            .filter(exchange, next -> {
                next.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return Mono.empty();
            })
            .block();
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
