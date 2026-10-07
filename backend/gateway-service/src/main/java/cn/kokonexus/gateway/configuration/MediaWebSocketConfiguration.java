package cn.kokonexus.gateway.configuration;

import cn.kokonexus.gateway.filter.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.socket.*;
import org.springframework.web.reactive.socket.server.*;

/** Gateway媒体WS使用真实HandshakeService并绑定持续核验；普通Netty聊天路由不改变。 */
@Configuration
public class MediaWebSocketConfiguration {

    @Bean
    public static BeanPostProcessor mediaWebSocketLeaseDecorator(ObjectProvider<MediaWebSocketLease> leases) {
        // Gateway无条件注册webSocketService，不能声明同名Bean或重新创建默认升级策略。
        // 延迟取得租约组件，避免BeanPostProcessor注册期提前初始化RPC/鉴权依赖。
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if ("webSocketService".equals(name) && bean instanceof WebSocketService service) {
                    return new LeasedService(service, leases);
                }
                return bean;
            }
        };
    }

    /** 显式转发Handshake生命周期，不因包装忽略底层升级策略的启动/停止。 */
    static class LeasedService implements WebSocketService, Lifecycle {

        /** 保留Gateway原始握手及已配置的升级策略、帧上限和Ping行为。 */ private final WebSocketService delegate;
        /** 媒体请求才解析的有界持续核验组件。 */ private final ObjectProvider<MediaWebSocketLease> leases;

        LeasedService(WebSocketService delegate, ObjectProvider<MediaWebSocketLease> leases) {
            this.delegate = delegate;
            this.leases = leases;
        }

        @Override
        public reactor.core.publisher.Mono<Void> handleRequest(
            org.springframework.web.server.ServerWebExchange exchange,
            WebSocketHandler handler
        ) {
            MediaConnectionProof proof = exchange.getAttribute(MediaConnectionProof.ATTRIBUTE);
            if (proof == null) return delegate.handleRequest(exchange, handler);
            return delegate.handleRequest(
                exchange,
                new WebSocketHandler() {
                    @Override
                    public java.util.List<String> getSubProtocols() {
                        return handler.getSubProtocols();
                    }

                    @Override
                    public reactor.core.publisher.Mono<Void> handle(WebSocketSession session) {
                        return leases.getObject().handle(session, handler, proof);
                    }
                }
            );
        }

        @Override
        public void start() {
            if (delegate instanceof Lifecycle lifecycle) lifecycle.start();
        }

        @Override
        public void stop() {
            if (delegate instanceof Lifecycle lifecycle) lifecycle.stop();
        }

        @Override
        public boolean isRunning() {
            return delegate instanceof Lifecycle lifecycle && lifecycle.isRunning();
        }
    }
}
