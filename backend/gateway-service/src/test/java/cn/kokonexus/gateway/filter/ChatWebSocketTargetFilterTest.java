package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 目标映射规则与错误关闭；真实发现/鉴权/网络由隔离 Boot 验证，不能由此替代。 */
class ChatWebSocketTargetFilterTest {

    /** 无外部推送的本地指标。 */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** 生产映射过滤器，不另造测试实现。 */
    private final ChatWebSocketTargetFilter filter = new ChatWebSocketTargetFilter(meters);

    @AfterEach
    void cleanup() {
        meters.close();
    }

    @Test
    void mapsSameSelectedNodeAndPreservesProtocolRawPathAndQuery() {
        for (String scheme : List.of("ws", "wss")) {
            var exchange = exchange("chat-websocket-service", "lb:" + scheme + "://koko-nexus-chat");
            URI target = URI.create(scheme + "://[::1]:8087/api/chat/ws?cursor=a%2Bb%2Fz");
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, target);
            choose(exchange, "koko-nexus-chat", "8098");
            var calls = new AtomicInteger();
            filter.filter(exchange, ignored -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).isEqualTo(
                URI.create(scheme + "://[::1]:8098/api/chat/ws?cursor=a%2Bb%2Fz")
            );
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void eachSelectedInstanceUsesItsOwnMetadataNotAFixedPeer() {
        for (String port : List.of("8097", "8098")) {
            var exchange = exchange("chat-websocket-service", "lb:ws://koko-nexus-chat");
            choose(exchange, "koko-nexus-chat", port);
            filter.filter(exchange, ignored -> Mono.empty()).block();
            assertThat(((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).getPort()).isEqualTo(
                Integer.parseInt(port)
            );
        }
    }

    @Test
    void invalidMetadataNeverReachesTransportOrLeaksConfiguration() {
        for (String port : List.of(
            "",
            "0",
            "-1",
            "+8097",
            "08097",
            " 8097",
            "65536",
            "999999",
            "ws://private-host:8097"
        )) {
            var exchange = exchange("chat-websocket-service", "lb:ws://koko-nexus-chat");
            choose(exchange, "koko-nexus-chat", port);
            var calls = new AtomicInteger();
            filter.filter(exchange, ignored -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls).hasValue(0);
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("CHAT_ROUTING_UNAVAILABLE")
                .doesNotContain("private-host");
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).getPort()).isEqualTo(8087);
        }
        assertThat(meters.get("koko.gateway.chat.websocket.invalid.targets").counter().count()).isEqualTo(9);
    }

    @Test
    void missingMetadataOrChoiceAndWrongServiceFailClosed() {
        for (int mode = 0; mode < 3; mode++) {
            var exchange = exchange("chat-websocket-service", "lb:ws://koko-nexus-chat");
            if (mode == 1) choose(exchange, "koko-nexus-chat", null);
            if (mode == 2) choose(exchange, "unrelated-service", "8097");
            filter.filter(exchange, ignored -> Mono.error(new AssertionError("Transport was invoked"))).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void unexpectedSchemeAndEmbeddedCredentialsCannotReachTransport() {
        for (String value : List.of(
            "ftp://localhost:8087/api/chat/ws",
            "ws://user:secret@localhost:8087/api/chat/ws"
        )) {
            var exchange = exchange("chat-websocket-service", "lb:ws://koko-nexus-chat");
            choose(exchange, "koko-nexus-chat", "8097");
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, URI.create(value));
            filter.filter(exchange, ignored -> Mono.error(new AssertionError("Transport was invoked"))).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void fixedWsRouteAndOtherHttpRoutesRemainUnchanged() {
        for (var exchange : List.of(
            exchange("chat-websocket-service", "ws://localhost:8097"),
            exchange("chat-service", "lb://koko-nexus-chat")
        )) {
            URI before = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
            var calls = new AtomicInteger();
            filter.filter(exchange, ignored -> Mono.fromRunnable(calls::incrementAndGet)).block();
            assertThat(calls).hasValue(1);
            assertThat((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).isEqualTo(before);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void readsChosenInstanceAtSubscriptionNotChainAssembly() {
        var exchange = exchange("chat-websocket-service", "lb:ws://koko-nexus-chat");
        var calls = new AtomicInteger();
        Mono<Void> result = filter.filter(exchange, ignored -> Mono.fromRunnable(calls::incrementAndGet));
        choose(exchange, "koko-nexus-chat", "65535");
        result.block();
        assertThat(calls).hasValue(1);
        assertThat(((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).getPort()).isEqualTo(65535);
    }

    @Test
    void actualNacosProjectionCannotOverrideWsOrDowngradeWssRoute() {
        for (String scheme : List.of("ws", "wss")) {
            var exchange = exchange("chat-websocket-service", "lb:" + scheme + "://koko-nexus-chat");
            var instance = new com.alibaba.cloud.nacos.NacosServiceInstance();
            instance.setServiceId("koko-nexus-chat");
            instance.setHost("127.0.0.1");
            instance.setPort(42197);
            instance.setMetadata(Map.of(ChatWebSocketTargetFilter.PORT_METADATA, "42997"));
            var delegated = new org.springframework.cloud.gateway.support.DelegatingServiceInstance(instance, scheme);
            URI resolved = org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools.reconstructURI(
                delegated,
                exchange.getRequest().getURI()
            );
            assertThat(resolved.getScheme()).isEqualTo("http"); // 实际依赖的返回值，不用默认实例桩掩盖。
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, resolved);
            exchange.getAttributes().put(GATEWAY_LOADBALANCER_RESPONSE_ATTR, new DefaultResponse(instance));
            filter.filter(exchange, ignored -> Mono.empty()).block();
            assertThat((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).isEqualTo(
                URI.create(scheme + "://127.0.0.1:42997/api/chat/ws")
            );
        }
    }

    @Test
    void httpOnlyLoadBalancedUriCannotSilentlyReachRestPort() {
        var exchange = exchange("chat-websocket-service", "lb://koko-nexus-chat");
        exchange.getAttributes().remove(GATEWAY_SCHEME_PREFIX_ATTR);
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, URI.create("http://localhost:8087/api/chat/ws"));
        choose(exchange, "koko-nexus-chat", "8097");
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("REST port was used for WS"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void orderingIsAfterActualLoadBalancerBeforeNetworkRouting() {
        assertThat(filter.getOrder()).isEqualTo(ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 1);
        assertThat(filter.getOrder()).isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1);
    }

    private static MockServerWebExchange exchange(String routeId, String uri) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://localhost/api/chat/ws"));
        exchange.getAttributes().put(
            GATEWAY_ROUTE_ATTR,
            Route.async()
                .id(routeId)
                .uri(uri)
                .predicate(ignored -> true)
                .build()
        );
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, URI.create("ws://localhost:8087/api/chat/ws"));
        if (uri.startsWith("lb:")) exchange.getAttributes().put(GATEWAY_SCHEME_PREFIX_ATTR, "lb");
        return exchange;
    }

    private static void choose(MockServerWebExchange exchange, String service, String port) {
        var instance = new DefaultServiceInstance(
            "isolated-node",
            service,
            "localhost",
            8087,
            false,
            port == null ? Map.of() : Map.of(ChatWebSocketTargetFilter.PORT_METADATA, port)
        );
        exchange.getAttributes().put(GATEWAY_LOADBALANCER_RESPONSE_ATTR, new DefaultResponse(instance));
    }
}
