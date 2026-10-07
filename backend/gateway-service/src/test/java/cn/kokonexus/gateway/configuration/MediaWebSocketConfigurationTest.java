package cn.kokonexus.gateway.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.gateway.filter.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.context.Lifecycle;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.*;
import reactor.core.publisher.Mono;

/** 实际Gateway自动配置注册握手Bean，覆盖重复Bean启动失败及原升级策略保留。 */
class MediaWebSocketConfigurationTest {

    @Test
    void decoratesGatewayBeanWithoutOverrideAndKeepsConfiguredUpgradeStrategy() {
        var strategy = mock(RequestUpgradeStrategy.class);
        when(strategy.upgrade(any(), any(), any(), any())).thenReturn(Mono.empty());
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CodecsAutoConfiguration.class,
                    SslAutoConfiguration.class,
                    WebFluxAutoConfiguration.class,
                    GatewayAutoConfiguration.class
                )
            )
            .withUserConfiguration(MediaWebSocketConfiguration.class)
            .withBean(RequestUpgradeStrategy.class, () -> strategy)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(WebSocketService.class);
                var service = context.getBean("webSocketService", WebSocketService.class);
                assertThat(service).isInstanceOf(MediaWebSocketConfiguration.LeasedService.class);
                // 无媒体证明的普通聊天路径仍调用自动配置注入的升级策略；不初始化媒体组件。
                var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/chat/ws")
                        .header("Upgrade", "websocket")
                        .header("Connection", "Upgrade")
                        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                        .header("Sec-WebSocket-Version", "13")
                );
                WebSocketHandler handler = session -> Mono.empty();
                service.handleRequest(exchange, handler).block();
                verify(strategy).upgrade(same(exchange), same(handler), isNull(), any());
                var lifecycle = (Lifecycle) service;
                lifecycle.start();
                assertThat(lifecycle.isRunning()).isTrue();
                lifecycle.stop();
                assertThat(lifecycle.isRunning()).isFalse();
            });
    }
}
