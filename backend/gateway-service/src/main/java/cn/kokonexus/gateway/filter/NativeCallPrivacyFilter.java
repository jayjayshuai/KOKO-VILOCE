package cn.kokonexus.gateway.filter;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 来电成员/凭据及错误均禁止缓存，先于鉴权与限流执行，不依赖下游正常响应。 */
@Component
@Order(-210)
public class NativeCallPrivacyFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (
            path.equals("/api/chat/calls") ||
            path.startsWith("/api/chat/calls/") ||
            path.startsWith("/api/media/native-call/")
        ) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().getHeaders().setPragma("no-cache");
        }
        return chain.filter(exchange);
    }
}
