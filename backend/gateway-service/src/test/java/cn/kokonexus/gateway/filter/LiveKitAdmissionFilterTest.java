package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.voice.*;
import cn.kokonexus.gateway.infrastructure.MediaAdmissionClient;
import java.net.URI;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 真实过滤器的可信身份/候选路径/有界调度检查，未替代真实网站会话/RPC/SFU集成。 */
class LiveKitAdmissionFilterTest {

    /** 合成非JWT，网络业务桩明确允许/拒绝。 */ private static final String TOKEN = "synthetic-rtc-token";
    /** 环回测试Origin，正式入口必须HTTPS。 */ private static final String ORIGIN = "http://127.0.0.1:45180";

    private MockServerWebExchange exchange(String path, String user) {
        var request = MockServerHttpRequest.get(path)
            .header("Origin", ORIGIN)
            .header("Upgrade", "websocket")
            .header("Cookie", "koko-nexus-token=synthetic-cookie")
            .header("koko-nexus-token", "synthetic-cookie")
            .header("X-Koko-User-Id", "forged-user")
            .header("X-Koko-Gateway-Key", "synthetic-internal-key");
        var exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async()
                .id("livekit-signal")
                .uri("ws://127.0.0.1:45181")
                .predicate(ignored -> true)
                .build()
        );
        if (user != null) exchange.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, user);
        return exchange;
    }

    private LiveKitAdmissionFilter filter(MediaAdmissionClient client, boolean enabled) {
        return new LiveKitAdmissionFilter(client, enabled, ORIGIN, "koko-nexus-token", 1, 1);
    }

    @Test
    void versionOneUsesSameIdentityJwtPrivacyAndLeaseProofAsLegacySignal() {
        var client = mock(MediaAdmissionClient.class);
        when(client.admit(any())).thenReturn(true);
        try (var gate = filter(client, true)) {
            var exchange = exchange(
                "/api/media/livekit/rtc/v1?access_token=" + TOKEN + "&join_request=synthetic-join-request",
                "42"
            );
            var forwarded = new AtomicBoolean();
            gate.filter(exchange, next ->
                Mono.fromRunnable(() -> {
                    forwarded.set(true);
                    assertThat(next.getRequest().getHeaders().getFirst("Cookie")).isNull();
                    assertThat(next.getRequest().getHeaders().getFirst("X-Koko-Gateway-Key")).isNull();
                    assertThat(next.getRequest().getQueryParams().getFirst("join_request")).isEqualTo(
                        "synthetic-join-request"
                    );
                    MediaConnectionProof proof = next.getAttribute(MediaConnectionProof.ATTRIBUTE);
                    assertThat(proof).isNotNull();
                })
            ).block();
            assertThat(forwarded).isTrue();
            verify(client).admit(
                new MediaAdmissionCommand("42", TOKEN, WebsiteSessionScope.fromToken("synthetic-cookie"))
            );
        }
    }

    @Test
    void versionOneAliasesDuplicatesAndWrongRouteNeverBypassAdmission() {
        var client = mock(MediaAdmissionClient.class);
        try (var gate = filter(client, true)) {
            for (var path : List.of(
                "/api/media/livekit/rtc%2Fv1",
                "/api/media/livekit/rtc/v1/extra",
                "/api/media/livekit/rtc//v1",
                "/api/media/livekit/rtc/v1?access_token=a&access_token=b"
            )) {
                var exchange = exchange(path, "42");
                gate.filter(exchange, next -> Mono.error(new AssertionError("Unexpected upstream"))).block();
                assertThat(exchange.getResponse().getStatusCode().is4xxClientError()).isTrue();
            }
            var wrongRoute = exchange("/api/media/livekit/rtc/v1?access_token=" + TOKEN, "42");
            wrongRoute.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async()
                    .id("livekit-validate")
                    .uri("http://127.0.0.1:45181")
                    .predicate(ignored -> true)
                    .build()
            );
            gate.filter(wrongRoute, next -> Mono.error(new AssertionError("Unexpected route forwarding"))).block();
            assertThat(wrongRoute.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(client);
    }

    @Test
    void versionOneValidationRechecksUserAndJwtWithoutCreatingSocketProof() {
        var client = mock(MediaAdmissionClient.class);
        when(client.admit(any())).thenReturn(true);
        try (var gate = filter(client, true)) {
            var exchange = exchange("/api/media/livekit/rtc/v1/validate?access_token=" + TOKEN, "42");
            exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async()
                    .id("livekit-validate")
                    .uri("http://127.0.0.1:45181")
                    .predicate(ignored -> true)
                    .build()
            );
            var reached = new AtomicBoolean();
            gate.filter(exchange, next ->
                Mono.fromRunnable(() -> {
                    reached.set(true);
                    MediaConnectionProof proof = next.getAttribute(MediaConnectionProof.ATTRIBUTE);
                    assertThat(proof).isNull();
                    assertThat(next.getRequest().getHeaders().getFirst("Cookie")).isNull();
                })
            ).block();
            assertThat(reached).isTrue();
            verify(client).admit(
                new MediaAdmissionCommand("42", TOKEN, WebsiteSessionScope.fromToken("synthetic-cookie"))
            );
        }
    }

    @Test
    void acceptedSignalBindsServerIdentityAndStripsCookieAndTrustedHeadersBeforeUpstream() {
        var client = mock(MediaAdmissionClient.class);
        when(client.admit(any())).thenReturn(true);
        try (var filter = filter(client, true)) {
            var exchange = exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42");
            var forwarded = new AtomicBoolean();
            filter
                .filter(exchange, next ->
                    Mono.fromRunnable(() -> {
                        forwarded.set(true);
                        assertThat(next.getRequest().getHeaders().getFirst("Cookie")).isNull();
                        assertThat(next.getRequest().getHeaders().getFirst("koko-nexus-token")).isNull();
                        assertThat(next.getRequest().getHeaders().getFirst("X-Koko-User-Id")).isNull();
                        assertThat(next.getRequest().getHeaders().getFirst("X-Koko-Gateway-Key")).isNull();
                        assertThat(next.getRequest().getQueryParams().getFirst("access_token")).isEqualTo(TOKEN);
                    })
                )
                .block();
            assertThat(forwarded).isTrue();
            verify(client).admit(
                new MediaAdmissionCommand("42", TOKEN, WebsiteSessionScope.fromToken("synthetic-cookie"))
            );
        }
    }

    @Test
    void disabledNoSessionAliasDuplicateTokenAndUntrustedOriginNeverReachRpcOrUpstream() {
        var client = mock(MediaAdmissionClient.class);
        try (var filter = filter(client, false)) {
            var exchange = exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42");
            filter.filter(exchange, next -> Mono.error(new AssertionError())).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        try (var filter = filter(client, true)) {
            for (var exchange : List.of(
                exchange("/api/media/livekit/rtc?access_token=" + TOKEN, null),
                exchange("/api/media/livekit/rtc%2Fvalidate?access_token=" + TOKEN, "42"),
                exchange("/api/media/livekit/rtc?access_token=a&access_token=b", "42"),
                exchange("/api/media/livekit/rtc", "42")
            )) {
                filter.filter(exchange, next -> Mono.error(new AssertionError())).block();
                assertThat(exchange.getResponse().getStatusCode().is4xxClientError()).isTrue();
            }
            var origin = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/media/livekit/rtc?access_token=" + TOKEN)
                    .header("Origin", "https://untrusted.invalid")
                    .header("Upgrade", "websocket")
            );
            origin.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async()
                    .id("livekit-signal")
                    .uri("ws://127.0.0.1:45181")
                    .predicate(ignored -> true)
                    .build()
            );
            origin.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
            filter.filter(origin, next -> Mono.error(new AssertionError())).block();
            assertThat(origin.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        verifyNoInteractions(client);
    }

    @Test
    void denialAndFailureAreDifferentAndDownstreamFailureIsNotRewrittenAsAdmissionError() {
        var client = mock(MediaAdmissionClient.class);
        try (var filter = filter(client, true)) {
            var denied = exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42");
            when(client.admit(any())).thenReturn(false);
            filter.filter(denied, next -> Mono.error(new AssertionError())).block();
            assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            when(client.admit(any())).thenThrow(new IllegalStateException("synthetic-private-credential"));
            var failed = exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42");
            filter.filter(failed, next -> Mono.error(new AssertionError())).block();
            assertThat(failed.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(failed.getResponse().getBodyAsString().block())
                .doesNotContain(TOKEN)
                .doesNotContain("private-credential");
            doReturn(true).when(client).admit(any());
            var downstream = exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42");
            assertThatThrownBy(() ->
                filter.filter(downstream, next -> Mono.error(new IllegalStateException("downstream"))).block()
            ).hasMessage("downstream");
            assertThat(downstream.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void blockingRpcRunsOnDedicatedPoolAndOverflowFailsClosed() throws Exception {
        var client = mock(MediaAdmissionClient.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        when(client.admit(any())).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return true;
        });
        try (var filter = filter(client, true)) {
            var first = filter
                .filter(exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42"), next -> Mono.empty())
                .toFuture();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = filter
                .filter(exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42"), next -> Mono.empty())
                .toFuture();
            var overflow = exchange("/api/media/livekit/rtc?access_token=" + TOKEN, "42");
            filter.filter(overflow, next -> Mono.error(new AssertionError())).block();
            assertThat(overflow.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(thread.get()).startsWith("koko-media-admission-");
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidOriginAndResourceConfigurationCannotStartAnEnabledGate() {
        var client = mock(MediaAdmissionClient.class);
        for (String origin : List.of(
            "",
            "http://untrusted.invalid",
            "https://example.invalid/path",
            "https://user@example.invalid",
            "https://example.invalid?token=x"
        ))
            assertThatThrownBy(() ->
                new LiveKitAdmissionFilter(client, true, origin, "koko-nexus-token", 1, 1)
            ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitAdmissionFilter(client, false, "", "koko-nexus-token", 17, 1)).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    @Test
    void ordinarySdkValidationAllowsSameSiteGetButAmbiguousCredentialsNeverPass() {
        var client = mock(MediaAdmissionClient.class);
        when(client.admit(any())).thenReturn(true);
        try (var filter = filter(client, true)) {
            var validate = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/media/livekit/rtc/validate?access_token=" + TOKEN).cookie(
                    new org.springframework.http.HttpCookie("koko-nexus-token", "synthetic-cookie")
                )
            );
            validate.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async()
                    .id("livekit-validate")
                    .uri("http://127.0.0.1:45181")
                    .predicate(ignored -> true)
                    .build()
            );
            validate.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
            var calls = new AtomicInteger();
            filter.filter(validate, next -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls).hasValue(1);
            clearInvocations(client);
            for (var bad : List.of(
                MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/media/livekit/rtc?access_token=" + TOKEN)
                        .header("Authorization", "Bearer synthetic-other")
                        .header("Origin", ORIGIN)
                        .header("Upgrade", "websocket")
                ),
                exchange("/api/media/livekit/rtc?access_token=" + TOKEN + "&koko-nexus-token=synthetic-cookie", "42")
            )) {
                bad.getAttributes().put(
                    ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                    Route.async()
                        .id("livekit-signal")
                        .uri("ws://127.0.0.1:45181")
                        .predicate(ignored -> true)
                        .build()
                );
                bad.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
                filter.filter(bad, next -> Mono.error(new AssertionError())).block();
                assertThat(bad.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
            verifyNoInteractions(client);
        }
    }
}
