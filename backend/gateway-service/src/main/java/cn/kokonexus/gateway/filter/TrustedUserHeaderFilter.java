package cn.kokonexus.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** gateway-service：TrustedUserHeaderFilter 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class TrustedUserHeaderFilter implements GlobalFilter, Ordered {

    /** USER_ID_HEADER 服务端协议常量，不接受客户端覆盖。 */
    public static final String USER_ID_HEADER = "X-Koko-User-Id";
    /** INTERNAL_KEY_HEADER 服务端协议常量，不接受客户端覆盖。 */
    public static final String INTERNAL_KEY_HEADER = "X-Koko-Gateway-Key";
    /** LOGIN_ID_ATTRIBUTE 服务端协议常量，不接受客户端覆盖。 */
    public static final String LOGIN_ID_ATTRIBUTE = "koko-nexus.login-id";
    /** 当前已认证网站会话摘要的内部属性，不来自HTTP身份头。 */
    public static final String WEBSITE_SCOPE_ATTRIBUTE = "koko-nexus.website-scope";
    /** 仅凭据签发下游接收的可信摘要头，所有客户端同名头先删除。 */
    public static final String WEBSITE_SCOPE_HEADER = "X-Koko-Website-Scope";
    /** 网关到下游的内部密钥，禁止日志输出。 */
    private final String internalKey;

    public TrustedUserHeaderFilter(@Value("${koko.gateway.internal-key:}") String internalKey) {
        this.internalKey = internalKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var requestBuilder = exchange
            .getRequest()
            .mutate()
            .headers(headers -> {
                headers.remove(USER_ID_HEADER);
                headers.remove(INTERNAL_KEY_HEADER);
                headers.remove(WEBSITE_SCOPE_HEADER);
            });
        String loginId = exchange.getAttribute(LOGIN_ID_ATTRIBUTE);
        if (loginId != null) {
            requestBuilder.header(USER_ID_HEADER, loginId);
        }
        String path = exchange.getRequest().getURI().getPath();
        String scope = exchange.getAttribute(WEBSITE_SCOPE_ATTRIBUTE);
        if (
            loginId != null &&
            path.matches("/api/voice/rooms/[1-9][0-9]{0,18}/interaction/media-credentials") &&
            cn.kokonexus.api.voice.WebsiteSessionScope.valid(scope)
        ) {
            requestBuilder.header(WEBSITE_SCOPE_HEADER, scope);
        }
        if (
            !internalKey.isBlank() &&
            (path.startsWith("/api/assets/") ||
                path.startsWith("/api/chat/") ||
                path.startsWith("/api/voice/") ||
                path.equals("/api/docs/voice/v3/api-docs"))
        ) {
            requestBuilder.header(INTERNAL_KEY_HEADER, internalKey);
        }
        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
