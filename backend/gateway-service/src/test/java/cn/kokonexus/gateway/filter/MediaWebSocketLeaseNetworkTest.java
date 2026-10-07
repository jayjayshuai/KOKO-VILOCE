package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.gateway.configuration.MediaWebSocketConfiguration;
import cn.kokonexus.gateway.infrastructure.*;
import java.net.URI;
import java.net.http.*;
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
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** 实际两端TCP WS与Gateway包装握手/资源回收；网站/RPC是明确桩，不证明Redis/语音DB/SFU。 */
class MediaWebSocketLeaseNetworkTest {

    @Test
    void periodicRevocationClosesBothProxyPeersAndReturnsSlotWithoutLeakingProof() throws Exception {
        var closed = new AtomicInteger();
        var retained = new AtomicInteger();
        var valid = new AtomicBoolean(true);
        var site = new AtomicBoolean(true);
        var thread = new AtomicReference<String>();
        var backend = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((in, out) ->
                out.sendWebsocket((receive, send) ->
                    send
                        .sendString(receive.receive().asString())
                        .then()
                        .doFinally(signal -> closed.incrementAndGet())
                )
            )
            .bindNow();
        var media = mock(MediaAdmissionClient.class);
        when(media.admit(any())).thenReturn(true);
        when(media.retain(any())).thenAnswer(call -> {
            thread.set(Thread.currentThread().getName());
            retained.incrementAndGet();
            return valid.get();
        });
        var website = mock(MediaWebsiteSessionCheck.class);
        when(website.active("synthetic-cookie", "42")).thenAnswer(call -> site.get());
        String origin = "http://127.0.0.1:45180";
        try (
            var guard = new MediaWebSocketLease(media, website, 50, 1, 1, 4);
            var admission = new LiveKitAdmissionFilter(media, true, origin, "koko-nexus-token", 1, 4)
        ) {
            var beans = new StaticListableBeanFactory(Map.of("mediaWebSocketLease", guard));
            var decorator = MediaWebSocketConfiguration.mediaWebSocketLeaseDecorator(
                beans.getBeanProvider(MediaWebSocketLease.class)
            );
            var socketService =
                (org.springframework.web.reactive.socket.server.WebSocketService) decorator.postProcessAfterInitialization(
                    new org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService(),
                    "webSocketService"
                );
            var strip = new StripPrefixGatewayFilterFactory();
            var config = new StripPrefixGatewayFilterFactory.Config();
            config.setParts(3);
            var route = Route.async()
                .id("livekit-signal")
                .uri("ws://127.0.0.1:" + backend.port())
                .predicate(ignored -> true)
                .filter(new OrderedGatewayFilter(strip.apply(config), 0))
                .build();
            var headers = new StaticListableBeanFactory().<List<HttpHeadersFilter>>getBeanProvider(
                ResolvableType.forClassWithGenerics(List.class, HttpHeadersFilter.class)
            );
            var router = new WebsocketRoutingFilter(new ReactorNettyWebSocketClient(), socketService, headers);
            var pipeline = new FilteringWebHandler(
                List.of(new TrustedUserHeaderFilter("synthetic-key"), admission, new RouteToRequestUrlFilter(), router),
                false
            );
            var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
                exchange.getAttributes().put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, "42");
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
                return pipeline.handle(exchange);
            })
                .exceptionHandler(new MediaSignalPrivacyExceptionHandler())
                .build();
            var gateway = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(handler))
                .bindNow();
            try (var browser = HttpClient.newHttpClient()) {
                URI uri = URI.create(
                    "ws://127.0.0.1:" + gateway.port() + "/api/media/livekit/rtc?access_token=synthetic-token"
                );
                var first = new Probe();
                var socket = connect(browser, uri, origin, first);
                socket.sendText("synthetic-echo", true).get(2, TimeUnit.SECONDS);
                assertThat(first.echo.get(2, TimeUnit.SECONDS)).isEqualTo("synthetic-echo");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (retained.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(retained.get()).isPositive();
                assertThat(thread.get()).startsWith("koko-media-lease-");
                var overflow = new Probe();
                connect(browser, uri, origin, overflow);
                assertThat(overflow.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1013);
                valid.set(false);
                assertThat(first.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (closed.get() < 1 && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(closed.get()).isPositive();
                valid.set(true);
                var after = new Probe();
                var resumed = connect(browser, uri, origin, after);
                resumed.sendText("synthetic-resumed", true).get(2, TimeUnit.SECONDS);
                assertThat(after.echo.get(2, TimeUnit.SECONDS)).isEqualTo("synthetic-resumed");
                site.set(false);
                assertThat(after.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                site.set(true);
                doThrow(new cn.kokonexus.api.voice.MediaAdmissionUnavailableException()).when(media).retain(any());
                var failure = new Probe();
                connect(browser, uri, origin, failure);
                assertThat(failure.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                assertThat(
                    new MediaConnectionProof(
                        new cn.kokonexus.api.voice.MediaAdmissionCommand("42", "synthetic-token"),
                        "synthetic-cookie"
                    ).toString()
                )
                    .doesNotContain("synthetic-token")
                    .doesNotContain("synthetic-cookie");
            } finally {
                gateway.disposeNow();
            }
        } finally {
            backend.disposeNow();
        }
    }

    private WebSocket connect(HttpClient client, URI uri, String origin, Probe listener) throws Exception {
        return client
            .newWebSocketBuilder()
            .header("Origin", origin)
            .header("Cookie", "koko-nexus-token=synthetic-cookie")
            .buildAsync(uri, listener)
            .get(3, TimeUnit.SECONDS);
    }

    /** 测试自身逐帧请求和正常收尾，不采集用户浏览器。 */
    static class Probe implements WebSocket.Listener {

        /** 实际回显帧。 */ final CompletableFuture<String> echo = new CompletableFuture<>();
        /** 实际网络关闭码。 */ final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence value, boolean last) {
            echo.complete(value.toString());
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
            echo.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }
}
