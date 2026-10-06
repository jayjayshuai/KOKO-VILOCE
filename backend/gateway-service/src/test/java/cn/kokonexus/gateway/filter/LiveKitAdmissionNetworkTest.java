package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.gateway.infrastructure.MediaAdmissionClient;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** 真环回HTTP/WS和Gateway实际过滤/StripPrefix/WS代理；会话、RPC及SFU为明确夹具。 */
class LiveKitAdmissionNetworkTest {

    @Test
    void actualWebsocketProxyStripsPrefixAndWebsiteSecretsAndRejectsDeniedHandshake() throws Exception {
        var upstreamCalls = new AtomicInteger();
        var upstreamUri = new AtomicReference<String>();
        var leaked = new AtomicBoolean();
        var unavailable = new AtomicBoolean();
        var upstream = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                upstreamCalls.incrementAndGet();
                upstreamUri.set(request.uri());
                leaked.set(
                    request.requestHeaders().contains("Cookie") ||
                        request.requestHeaders().contains("koko-nexus-token") ||
                        request.requestHeaders().contains("X-Koko-User-Id") ||
                        request.requestHeaders().contains("X-Koko-Gateway-Key")
                );
                if (unavailable.get()) return response.status(503).sendString(Mono.just("synthetic-unavailable"));
                return response.sendWebsocket((in, out) -> out.sendString(in.receive().asString()));
            })
            .bindNow();
        var client = mock(MediaAdmissionClient.class);
        when(client.admit(any())).thenAnswer(invocation ->
            "synthetic-allowed".equals(
                ((cn.kokonexus.api.voice.MediaAdmissionCommand) invocation.getArgument(0)).token()
            )
        );
        String origin = "http://127.0.0.1:45180";
        try (var gate = new LiveKitAdmissionFilter(client, true, origin, "koko-nexus-token", 2, 4)) {
            var strip = new StripPrefixGatewayFilterFactory();
            var config = new StripPrefixGatewayFilterFactory.Config();
            config.setParts(3);
            var route = Route.async()
                .id("livekit-signal")
                .uri("ws://127.0.0.1:" + upstream.port())
                .predicate(ignored -> true)
                .filter(new OrderedGatewayFilter(strip.apply(config), 0))
                .build();
            var headers = new StaticListableBeanFactory().<List<HttpHeadersFilter>>getBeanProvider(
                ResolvableType.forClassWithGenerics(List.class, HttpHeadersFilter.class)
            );
            var router = new WebsocketRoutingFilter(
                new ReactorNettyWebSocketClient(),
                new HandshakeWebSocketService(),
                headers
            );
            var filtering = new FilteringWebHandler(
                List.of(
                    new TrustedUserHeaderFilter("synthetic-internal-key"),
                    gate,
                    new RouteToRequestUrlFilter(),
                    router
                ),
                false
            );
            var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
                if (!exchange.getRequest().getURI().getRawPath().equals("/api/media/livekit/rtc")) {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                }
                // 明确身份夹具，不声称真实Sa-Token/Redis：只认固定测试Cookie，忽略客户端身份头。
                var cookie = exchange.getRequest().getCookies().getFirst("koko-nexus-token");
                if (cookie != null && cookie.getValue().equals("synthetic-cookie")) exchange
                    .getAttributes()
                    .put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
                return filtering.handle(exchange);
            })
                .exceptionHandler(new MediaSignalPrivacyExceptionHandler())
                .build();
            var gateway = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(handler))
                .bindNow();
            try (var browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                var listener = new EchoListener();
                var socket = browser
                    .newWebSocketBuilder()
                    .header("Origin", origin)
                    .header("Cookie", "koko-nexus-token=synthetic-cookie")
                    .header("X-Koko-User-Id", "43")
                    .header("X-Koko-Gateway-Key", "forged-key")
                    .buildAsync(
                        URI.create(
                            "ws://127.0.0.1:" +
                                gateway.port() +
                                "/api/media/livekit/rtc?access_token=synthetic-allowed&protocol=15"
                        ),
                        listener
                    )
                    .get(5, TimeUnit.SECONDS);
                socket.sendText("synthetic-protocol-frame", true).get(3, TimeUnit.SECONDS);
                assertThat(listener.message.get(3, TimeUnit.SECONDS)).isEqualTo("synthetic-protocol-frame");
                assertThat(upstreamUri.get()).isEqualTo("/rtc?access_token=synthetic-allowed&protocol=15");
                assertThat(leaked).isFalse();
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
                listener.closed.get(3, TimeUnit.SECONDS);
                try {
                    browser
                        .newWebSocketBuilder()
                        .header("Origin", origin)
                        .header("Cookie", "koko-nexus-token=synthetic-cookie")
                        .buildAsync(
                            URI.create(
                                "ws://127.0.0.1:" +
                                    gateway.port() +
                                    "/api/media/livekit/rtc?access_token=synthetic-denied"
                            ),
                            new EchoListener()
                        )
                        .get(3, TimeUnit.SECONDS);
                    throw new AssertionError("Denied WS was upgraded");
                } catch (ExecutionException denied) {
                    assertThat(denied.getCause()).isInstanceOf(WebSocketHandshakeException.class);
                    assertThat(((WebSocketHandshakeException) denied.getCause()).getResponse().statusCode()).isEqualTo(
                        403
                    );
                }
                assertThat(upstreamCalls).hasValue(1);
                var direct = browser.send(
                    HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:" +
                                gateway.port() +
                                "/api/media/livekit/rtc?access_token=synthetic-allowed"
                        )
                    )
                        .header("Cookie", "koko-nexus-token=synthetic-cookie")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                );
                assertThat(direct.statusCode()).isEqualTo(400);
                // 已向客户端升级后供应商握手失败：实际连接必须关闭，不能写JSON或无限等待。
                unavailable.set(true);
                var failedListener = new EchoListener();
                var failedSocket = browser
                    .newWebSocketBuilder()
                    .header("Origin", origin)
                    .header("Cookie", "koko-nexus-token=synthetic-cookie")
                    .buildAsync(
                        URI.create(
                            "ws://127.0.0.1:" + gateway.port() + "/api/media/livekit/rtc?access_token=synthetic-allowed"
                        ),
                        failedListener
                    )
                    .get(5, TimeUnit.SECONDS);
                // Reactor Netty可主动发1002错误关闭帧，也可能仅断TCP(1006)；两者均须有界关闭而非伪装正常完成。
                assertThat(failedListener.closed.get(3, TimeUnit.SECONDS)).isIn(1002, 1006);
                assertThat(failedListener.message.isDone()).isFalse();
                failedSocket.abort();
            } finally {
                gateway.disposeNow();
            }
        } finally {
            upstream.disposeNow();
        }
    }

    /** 无头协议观察器，不采集浏览器用户数据；明确请求下一帧并等待正常关闭。 */
    static class EchoListener implements WebSocket.Listener {

        /** 本轮完整回显文本。 */ final CompletableFuture<String> message = new CompletableFuture<>();
        /** 确认双方会话正常结束。 */ final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence value, boolean last) {
            message.complete(value.toString());
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            message.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }
}
