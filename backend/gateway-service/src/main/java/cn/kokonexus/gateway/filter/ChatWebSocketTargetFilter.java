package cn.kokonexus.gateway.filter;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/** 把已选择的聊天 HTTP 实例映射到同节点 Netty 端口；不再次选择节点或接受客户端目标。 */
@Component
public final class ChatWebSocketTargetFilter implements GlobalFilter, Ordered {

    /** 与聊天 Nacos HTTP 注册一致的内部端口元数据键，不接受请求头覆盖。 */
    public static final String PORT_METADATA = "koko-chat-websocket-port";
    /** 只处理已有聊天 WS 路由，其他 HTTP/文档路由保持原语义。 */
    private static final String ROUTE_ID = "chat-websocket-service";
    /** 固定领域服务名，不允许元数据控制目标域名或协议。 */
    private static final String SERVICE_ID = "koko-nexus-chat";
    /** 固定失败体，不泄露节点地址、注册元数据或会话。 */
    private static final byte[] UNAVAILABLE =
        "{\"code\":\"CHAT_ROUTING_UNAVAILABLE\",\"message\":\"聊天连接暂不可用，请稍后重试\"}".getBytes(
            StandardCharsets.UTF_8
        );
    /** 无节点/用户标签的配置失败计数；不是握手成功或客户端送达数。 */
    private final Counter invalidTargets;

    public ChatWebSocketTargetFilter(MeterRegistry meters) {
        invalidTargets = meters.counter("koko.gateway.chat.websocket.invalid.targets");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 延迟读取：负载均衡响应只会在上游 Mono 订阅后产生，不能在链组装阶段取空快照。
        return Mono.defer(() -> {
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            if (
                route == null ||
                !ROUTE_ID.equals(route.getId()) ||
                (!"lb".equals(route.getUri().getScheme()) &&
                    !"lb".equals(exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR)))
            ) {
                return chain.filter(exchange); // 定址入口不要求元数据，保持现有发布兼容。
            }
            URI wsRoute = URI.create(route.getUri().getSchemeSpecificPart());
            String wsScheme = wsRoute.getScheme();
            if (
                !("ws".equals(wsScheme) || "wss".equals(wsScheme)) ||
                !SERVICE_ID.equals(wsRoute.getHost()) ||
                wsRoute.getUserInfo() != null
            ) {
                return unavailable(exchange);
            }
            URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
            Response<?> choice = exchange.getAttribute(GATEWAY_LOADBALANCER_RESPONSE_ATTR);
            if (
                target == null ||
                !(
                    "http".equals(target.getScheme()) ||
                    "https".equals(target.getScheme()) ||
                    "ws".equals(target.getScheme()) ||
                    "wss".equals(target.getScheme())
                ) ||
                target.getHost() == null ||
                target.getUserInfo() != null ||
                choice == null ||
                !choice.hasServer() ||
                !(choice.getServer() instanceof ServiceInstance instance) ||
                !SERVICE_ID.equals(instance.getServiceId())
            ) {
                return unavailable(exchange);
            }
            String encodedPort = instance.getMetadata() == null ? null : instance.getMetadata().get(PORT_METADATA);
            if (encodedPort == null || !encodedPort.matches("[1-9][0-9]{0,4}")) return unavailable(exchange);
            int port = Integer.parseInt(encodedPort);
            if (port > 65535) return unavailable(exchange);
            // Nacos 实例显式返回 HTTP scheme，会覆盖 SCG 的 ws override；必须保留路由声明的 WS/WSS。
            // 保留已选主机和请求原始编码路径/查询，不把元数据当 URI 拼接，也不降低显式 WSS。
            URI mapped = UriComponentsBuilder.fromUri(target).scheme(wsScheme).port(port).build(true).toUri();
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, mapped);
            return chain.filter(exchange);
        });
    }

    private Mono<Void> unavailable(ServerWebExchange exchange) {
        invalidTargets.increment();
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(UNAVAILABLE)));
    }

    @Override
    public int getOrder() {
        return ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 1;
    }
}
