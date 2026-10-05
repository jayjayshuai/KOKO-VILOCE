package cn.kokonexus.gateway.filter;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** gateway-service：ApiRateLimitFilter 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class ApiRateLimitFilter implements WebFilter, Ordered {

    /** Redis 原子限流脚本，计数与过期同时设置；保持文本块原始字面量，避免格式化改写脚本缩进。 */
    // prettier-ignore
    private static final RedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return { current, redis.call('PTTL', KEYS[1]) }
            """, List.class);
    /** 限流时间窗口。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Redis 客户端，访问必须设置超时并明确故障策略。 */
    private final ReactiveStringRedisTemplate redis;
    /** 监控指标注册器。 */
    private final MeterRegistry meterRegistry;

    public ApiRateLimitFilter(ReactiveStringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        RateLimitPolicy policy = resolvePolicy(exchange.getRequest());
        if (policy == null) {
            return chain.filter(exchange);
        }
        String subject = subject(exchange);
        String key = "koko:nexus:rate:" + policy.bucket() + ":" + subject;
        return redis
            .execute(INCREMENT_SCRIPT, List.of(key), List.of(String.valueOf(WINDOW.toMillis())))
            .next()
            .onErrorReturn(List.of(-1L, 0L))
            .defaultIfEmpty(List.of(-1L, 0L))
            .flatMap(result ->
                ((Number) result.get(0)).longValue() < 0
                    ? redisUnavailable(exchange, chain, policy)
                    : applyDecision(exchange, chain, policy, result)
            );
    }

    Mono<Void> applyDecision(ServerWebExchange exchange, WebFilterChain chain, RateLimitPolicy policy, List result) {
        long current = ((Number) result.get(0)).longValue();
        long ttlMillis = Math.max(((Number) result.get(1)).longValue(), 1L);
        long remaining = Math.max(policy.capacity() - current, 0L);
        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(policy.capacity()));
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(remaining));
        if (current <= policy.capacity()) {
            meterRegistry.counter("koko.gateway.ratelimit", "result", "allowed", "bucket", policy.bucket()).increment();
            return chain.filter(exchange);
        }
        exchange
            .getResponse()
            .getHeaders()
            .set("Retry-After", String.valueOf((ttlMillis + 999L) / 1000L));
        meterRegistry.counter("koko.gateway.ratelimit", "result", "rejected", "bucket", policy.bucket()).increment();
        return json(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后再试");
    }

    RateLimitPolicy resolvePolicy(ServerHttpRequest request) {
        String path = request.getPath().value();
        if (!path.startsWith("/api/")) {
            return null;
        }
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            return new RateLimitPolicy("auth", 10, true);
        }
        HttpMethod method = request.getMethod();
        if (method != null && method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS) {
            return new RateLimitPolicy("write", 60, true);
        }
        return new RateLimitPolicy("read", 240, false);
    }

    private Mono<Void> redisUnavailable(ServerWebExchange exchange, WebFilterChain chain, RateLimitPolicy policy) {
        meterRegistry.counter("koko.gateway.ratelimit", "result", "store_error", "bucket", policy.bucket()).increment();
        if (!policy.failClosed()) {
            return chain.filter(exchange);
        }
        return json(exchange, HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE", "请求保护服务暂时不可用");
    }

    private String subject(ServerWebExchange exchange) {
        String loginId = exchange.getAttribute(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE);
        if (loginId != null && loginId.matches("[0-9]{1,24}")) {
            return "user-" + loginId;
        }
        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp == null || realIp.isBlank()) {
            InetSocketAddress address = exchange.getRequest().getRemoteAddress();
            realIp = address == null ? "unknown" : address.getAddress().getHostAddress();
        }
        return "ip-" + realIp.replaceAll("[^0-9a-fA-F:.-]", "_");
    }

    private Mono<Void> json(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -90;
    }

    /** gateway-service：RateLimitPolicy 领域类型；字段单位、状态及可空性见各属性说明。 */
    record RateLimitPolicy(
        @io.swagger.v3.oas.annotations.media.Schema(description = "限流策略桶标识，auth/read/write") String bucket,
        @io.swagger.v3.oas.annotations.media.Schema(description = "限流窗口允许的请求数") long capacity,
        @io.swagger.v3.oas.annotations.media.Schema(description = "限流依赖不可用时是否拒绝请求") boolean failClosed
    ) {}
}
