package cn.kokonexus.gateway.filter;

import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** 只诊断固定语音事实接口的失败/取消，不采集身份、查询串、Cookie、JWT或请求体。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class VoiceReadDiagnosticsFilter implements WebFilter {

    /** 只允许四种固定端点，绝不命中包含JWT的媒体WS或认证接口。 */
    private static final Pattern ROUTE = Pattern.compile(
        "^/api/voice/rooms/[1-9][0-9]{0,18}/interaction/(sync|media-plan|heartbeat|capabilities)$"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var match = ROUTE.matcher(exchange.getRequest().getPath().value());
        if (!match.matches()) return chain.filter(exchange);
        String endpoint = match.group(1);
        return Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> {
            if (signal == SignalType.CANCEL) {
                log.info("语音请求终止 endpoint={} outcome=cancelled", endpoint);
                return;
            }
            var status = exchange.getResponse().getStatusCode();
            if (status != null && status.value() >= 400) log.warn(
                "语音请求失败 endpoint={} status={}",
                endpoint,
                status.value()
            );
            else if (signal == SignalType.ON_ERROR) log.warn(
                "语音请求失败 endpoint={} outcome=unresolved-error",
                endpoint
            );
        });
    }
}
