package cn.kokonexus.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 媒体查询串含JWT：本入口异常不交给会打印完整URI的通用500日志；不吞其他路由错误。 */
@Component
@lombok.extern.slf4j.Slf4j
public class MediaSignalPrivacyExceptionHandler implements WebExceptionHandler, Ordered {

    /** 与准入过滤器相同的固定候选路由，不拦截普通业务异常。 */
    private static final Set<String> ROUTES = Set.of("livekit-signal", "livekit-validate");

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || !ROUTES.contains(route.getId())) return Mono.error(error);
        var response = exchange.getResponse();
        Object nativeResponse = ServerHttpResponseDecorator.getNativeResponse(response);
        boolean upgraded =
            response.isCommitted() ||
            (nativeResponse instanceof reactor.netty.http.server.HttpServerResponse actual && actual.hasSentHeaders());
        // 仅固定路由ID和布尔值，仍留下故障事件，不打印原异常/URI/帧/用户或媒体凭据。
        log.warn("媒体信令失败，route={}，responseStarted={}", route.getId(), upgraded);
        if (upgraded) {
            // WS已升级不能写JSON；只关闭本次Netty连接，避免客户端永远等待也不记录含JWT的URI。
            if (nativeResponse instanceof reactor.netty.http.server.HttpServerResponse netty) netty.withConnection(
                connection -> connection.dispose()
            );
            return Mono.empty();
        }
        response.setStatusCode(HttpStatus.BAD_GATEWAY);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        byte[] value = "{\"code\":\"MEDIA_SIGNAL_UNAVAILABLE\",\"message\":\"媒体信令连接未获确认\"}".getBytes(
            StandardCharsets.UTF_8
        );
        return response.writeWith(Mono.just(response.bufferFactory().wrap(value)));
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
