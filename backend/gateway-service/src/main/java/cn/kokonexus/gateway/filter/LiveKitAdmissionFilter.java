package cn.kokonexus.gateway.filter;

import cn.kokonexus.api.voice.MediaAdmissionCommand;
import cn.kokonexus.gateway.infrastructure.MediaAdmissionClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

/** LiveKit信令候选入口：网站会话＋JWT当前核验，管理API永不代理，RPC不占Netty EventLoop。 */
@Component
public class LiveKitAdmissionFilter implements GlobalFilter, Ordered, AutoCloseable {

    /** 仅这两条固定路由，编码别名不能绕过核验。 */
    private static final Set<String> ROUTES = Set.of("livekit-signal", "livekit-validate");
    /** SDK新旧协议均走相同准入；不可用通配路径扩大为LiveKit管理API代理。 */ private static final String BASE =
        "/api/media/livekit";
    /** 精确版本路径，拒绝编码别名、额外段和双斜线。 */ private static final Set<String> SIGNAL_PATHS = Set.of(
        BASE + "/rtc",
        BASE + "/rtc/v1"
    );
    /** SDK验证失败端点仍要求同一个网站身份和当前媒体凭据。 */ private static final Set<String> VALIDATION_PATHS =
        Set.of(BASE + "/rtc/validate", BASE + "/rtc/v1/validate");
    /** 不共享认证池，关闭释放；等待上限是每线程数。 */ private final Scheduler worker;
    /** 同源浏览器准入来源白名单，不信任任意Origin/X-Forwarded-Host。 */ private final Set<String> origins;
    /** voice域RPC适配器，不接收客户端身份。 */ private final MediaAdmissionClient client;
    /** 网站会话请求头与Cookie名称，转发前移除。 */ private final String sessionTokenName;
    /** 未验收的新信令入口默认关闭。 */ private final boolean enabled;

    public LiveKitAdmissionFilter(
        MediaAdmissionClient client,
        @Value("${koko.gateway.media-admission.enabled:false}") boolean enabled,
        @Value("${koko.gateway.media-admission.allowed-origins:}") String configuredOrigins,
        @Value("${sa-token.token-name:koko-nexus-token}") String sessionTokenName,
        @Value("${koko.gateway.media-admission.workers:2}") int workers,
        @Value("${koko.gateway.media-admission.queued-tasks-per-thread:16}") int queue
    ) {
        if (workers < 1 || workers > 16 || queue < 1 || queue > 64) throw new IllegalArgumentException(
            "媒体准入线程/队列超界"
        );
        var allow = new HashSet<String>();
        if (enabled) for (String origin : configuredOrigins.split(",")) {
            URI uri = URI.create(origin.trim());
            String host = uri.getHost();
            boolean local = Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(host == null ? "" : host);
            if (
                host == null ||
                uri.getUserInfo() != null ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null ||
                !uri.getPath().isEmpty() ||
                !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))
            ) throw new IllegalArgumentException("媒体准入来源必须为HTTPS站点，环回开发除外");
            allow.add(origin.trim());
        }
        if (enabled && allow.isEmpty()) throw new IllegalArgumentException("媒体准入来源未配置");
        this.origins = Set.copyOf(allow);
        this.enabled = enabled;
        this.client = client;
        this.sessionTokenName = sessionTokenName;
        this.worker = Schedulers.newBoundedElastic(workers, queue, "koko-media-admission", 60, true);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || !ROUTES.contains(route.getId())) return chain.filter(exchange);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (!enabled) return reject(exchange, 503, "MEDIA_ADMISSION_UNAVAILABLE");
        String path = exchange.getRequest().getURI().getRawPath();
        boolean socket = SIGNAL_PATHS.contains(path) && "livekit-signal".equals(route.getId());
        boolean validation = VALIDATION_PATHS.contains(path) && "livekit-validate".equals(route.getId());
        if (exchange.getRequest().getMethod() != HttpMethod.GET || (!socket && !validation)) return reject(
            exchange,
            400,
            "MEDIA_ADMISSION_INVALID"
        );
        var request = exchange.getRequest();
        if (socket && !"websocket".equalsIgnoreCase(request.getHeaders().getUpgrade())) return reject(
            exchange,
            400,
            "MEDIA_ADMISSION_INVALID"
        );
        var origin = request.getHeaders().getOrEmpty(HttpHeaders.ORIGIN);
        if (
            (socket && origin.size() != 1) ||
            origin.size() > 1 ||
            (!origin.isEmpty() && !origins.contains(origin.getFirst()))
        ) return reject(exchange, 403, "MEDIA_ADMISSION_DENIED");
        String user = exchange.getAttribute(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE);
        if (user == null || !user.matches("[1-9][0-9]{0,18}")) return reject(exchange, 401, "MEDIA_ADMISSION_REQUIRED");
        var values = request.getQueryParams().get("access_token");
        String rawQuery = request.getURI().getRawQuery();
        if (
            values == null ||
            values.size() != 1 ||
            values.getFirst().isBlank() ||
            values.getFirst().length() > 8192 ||
            rawQuery == null ||
            rawQuery.length() > 16384 ||
            request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION) ||
            request.getQueryParams().containsKey(sessionTokenName)
        ) return reject(exchange, 400, "MEDIA_ADMISSION_INVALID");
        var command = new MediaAdmissionCommand(user, values.getFirst());
        var siteHeaders = request.getHeaders().getOrEmpty(sessionTokenName);
        var siteCookies = request.getCookies().get(sessionTokenName);
        if (siteHeaders.size() > 1 || (siteCookies != null && siteCookies.size() > 1)) return reject(
            exchange,
            400,
            "MEDIA_ADMISSION_INVALID"
        );
        String cookie = siteCookies == null || siteCookies.isEmpty() ? null : siteCookies.getFirst().getValue();
        String websiteToken = siteHeaders.isEmpty() ? cookie : siteHeaders.getFirst();
        if (websiteToken == null || websiteToken.isBlank() || websiteToken.length() > 8192) return reject(
            exchange,
            401,
            "MEDIA_ADMISSION_REQUIRED"
        );
        if (cookie != null && !cookie.equals(websiteToken)) return reject(exchange, 400, "MEDIA_ADMISSION_INVALID");
        // 只捕获本次鉴权的故障；下游传输错误不伪装为鉴权成功或改写已升级响应。
        return Mono.fromCallable(() -> client.admit(command))
            .subscribeOn(worker)
            .timeout(Duration.ofSeconds(3))
            .onErrorResume(error -> Mono.empty())
            .flatMap(allowed -> {
                if (!allowed) return reject(exchange, 403, "MEDIA_ADMISSION_DENIED").thenReturn(false);
                if (socket) exchange
                    .getAttributes()
                    .put(MediaConnectionProof.ATTRIBUTE, new MediaConnectionProof(command, websiteToken));
                var upstream = request
                    .mutate()
                    .headers(headers -> {
                        headers.remove(HttpHeaders.COOKIE);
                        headers.remove(sessionTokenName);
                        headers.remove(TrustedUserHeaderFilter.USER_ID_HEADER);
                        headers.remove(TrustedUserHeaderFilter.INTERNAL_KEY_HEADER);
                    })
                    .build();
                return chain.filter(exchange.mutate().request(upstream).build()).thenReturn(true);
            })
            .switchIfEmpty(Mono.defer(() -> reject(exchange, 503, "MEDIA_ADMISSION_UNAVAILABLE").thenReturn(false)))
            .then();
    }

    private Mono<Void> reject(ServerWebExchange exchange, int status, String code) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        if (status == 503) response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        byte[] value = ("{\"code\":\"" + code + "\",\"message\":\"媒体准入未获确认\"}").getBytes(
            StandardCharsets.UTF_8
        );
        return response.writeWith(Mono.just(response.bufferFactory().wrap(value)));
    }

    @Override
    public int getOrder() {
        return -90;
    }

    @Override
    public void close() {
        worker.dispose();
    }
}
