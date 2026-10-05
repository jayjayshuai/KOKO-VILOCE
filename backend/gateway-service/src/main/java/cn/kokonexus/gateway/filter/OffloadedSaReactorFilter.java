package cn.kokonexus.gateway.filter;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.util.SaTokenConsts;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 将 Sa-Token 的同步 Redis 会话访问隔离到有界工作池，不占用 Netty EventLoop。
 * 保留官方过滤器的路由、错误钩子和同步上下文清理；只处理调度阶段的拒绝，不吞下游错误。
 */
@Order(SaTokenConsts.ASSEMBLY_ORDER)
public final class OffloadedSaReactorFilter extends SaReactorFilter implements AutoCloseable {

    /** 单实例最大鉴权工作线程数，避免错误配置耗尽主机线程。 */
    private static final int MAX_WORKERS = 32;
    /** Reactor 有界弹性池每工作线程允许等待的任务数上限，不是全局请求数。 */
    private static final int MAX_QUEUED_TASKS_PER_THREAD = 256;
    /** 饱和响应只含固定文案，不携带会话、请求内容或底层异常。 */
    private static final byte[] BUSY_BODY = (
        "{\"code\":\"AUTH_BUSY\"," + "\"message\":\"鉴权服务繁忙，请稍后重试\"}"
    ).getBytes(StandardCharsets.UTF_8);
    /** 本过滤器独占的有界线程池，随 Spring Bean 销毁释放，不使用全局共享池。 */
    private final Scheduler authenticationScheduler;

    public OffloadedSaReactorFilter(int workers, int queuedTasksPerThread) {
        if (
            workers < 1 ||
            workers > MAX_WORKERS ||
            queuedTasksPerThread < 1 ||
            queuedTasksPerThread > MAX_QUEUED_TASKS_PER_THREAD
        ) {
            throw new IllegalArgumentException("鉴权工作线程须为 1..32，每线程等待任务须为 1..256");
        }
        authenticationScheduler = Schedulers.newBoundedElastic(workers, queuedTasksPerThread, "koko-auth", 60, true);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.fromCallable(() -> super.filter(exchange, authorized -> Mono.defer(() -> chain.filter(authorized))))
            .subscribeOn(authenticationScheduler)
            // 必须位于 flatMap 前：下游异步/同步拒绝不是鉴权池饱和，不能改写为 AUTH_BUSY。
            .onErrorResume(RejectedExecutionException.class, error -> Mono.just(busy(exchange)))
            .flatMap(Function.identity());
    }

    private Mono<Void> busy(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(BUSY_BODY)));
    }

    /** Spring 关闭时释放本实例线程池；重复调用安全，不释放其他组件的调度器。 */
    @Override
    public void close() {
        authenticationScheduler.dispose();
    }
}
