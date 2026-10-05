package cn.kokonexus.gateway.filter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 运营权限、事件摘要、确认秘密不得进入浏览器/代理缓存；不代替 TLS 与权限验证。 */
@Component
@Order(-210)
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
public class OperationsResponsePrivacyFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/api/operations/")) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().getHeaders().set("Pragma", "no-cache");
        }
        return chain.filter(exchange);
    }
}
