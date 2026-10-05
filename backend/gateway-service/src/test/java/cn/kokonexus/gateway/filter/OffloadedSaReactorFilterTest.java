package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.fun.strategy.SaCreateSaRequestFunction;
import cn.dev33.satoken.fun.strategy.SaCreateSaResponseFunction;
import cn.dev33.satoken.fun.strategy.SaCreateSaStorageFunction;
import cn.dev33.satoken.fun.strategy.SaRouteMatchFunction;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.spring.SaTokenContextForSpringReactor;
import cn.dev33.satoken.reactor.spring.SaTokenContextRegister;
import cn.dev33.satoken.strategy.SaStrategy;
import cn.kokonexus.gateway.configuration.SaTokenGatewayConfiguration;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 实际 Netty EventLoop/有界 Reactor 调度/官方 Sa-Token 上下文；不宣称 Redis 网络或容量验收。 */
class OffloadedSaReactorFilterTest {

    /** 本测试前的全局上下文，退出时恢复。 */
    private SaTokenContext previousContext;
    /** 官方请求创建策略的原值，退出时恢复。 */
    private SaCreateSaRequestFunction previousRequestFactory;
    /** 官方响应创建策略的原值。 */
    private SaCreateSaResponseFunction previousResponseFactory;
    /** 官方存储创建策略的原值。 */
    private SaCreateSaStorageFunction previousStorageFactory;
    /** 官方路由匹配策略的原值。 */
    private SaRouteMatchFunction previousRouteMatcher;

    @BeforeEach
    void installRealReactorContext() {
        previousContext = SaManager.getSaTokenContext();
        previousRequestFactory = SaStrategy.instance.createSaRequest;
        previousResponseFactory = SaStrategy.instance.createSaResponse;
        previousStorageFactory = SaStrategy.instance.createSaStorage;
        previousRouteMatcher = SaStrategy.instance.routeMatcher;
        new SaTokenContextRegister();
        SaManager.setSaTokenContext(new SaTokenContextForSpringReactor());
    }

    @AfterEach
    void restoreGlobalContext() {
        SaManager.setSaTokenContext(previousContext);
        SaStrategy.instance.createSaRequest = previousRequestFactory;
        SaStrategy.instance.createSaResponse = previousResponseFactory;
        SaStrategy.instance.createSaStorage = previousStorageFactory;
        SaStrategy.instance.routeMatcher = previousRouteMatcher;
    }

    @Test
    void blockingAuthenticationCannotBlockActualNettyEventLoopAndContextIsCleared() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var authenticationThread = new AtomicReference<String>();
        var boundExchange = new AtomicBoolean();
        var contextCleared = new AtomicBoolean();
        var eventLoop = new DefaultEventLoop(new DefaultThreadFactory("auth-event-loop-fixture", true));
        try (var filter = filter(1, 4)) {
            var exchange = exchange();
            filter.setAuth(ignored -> {
                authenticationThread.set(Thread.currentThread().getName());
                boundExchange.set(SaReactorSyncHolder.getExchange() == exchange);
                entered.countDown();
                await(release);
            });
            CompletableFuture<Void> response = eventLoop
                .submit(() ->
                    filter
                        .filter(exchange, authorized -> {
                            contextCleared.set(!SaManager.getSaTokenContext().isValid());
                            return Mono.empty();
                        })
                        .toFuture()
                )
                .get(2, TimeUnit.SECONDS);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                // 鉴权仍被锁住时，原 EventLoop 必须可以继续执行其他工作。
                assertThat(
                    eventLoop
                        .submit(() -> {
                            return eventLoop.inEventLoop();
                        })
                        .get(2, TimeUnit.SECONDS)
                ).isTrue();
                assertThat(response.isDone()).isFalse();
                assertThat(authenticationThread.get()).startsWith("koko-auth-");
                assertThat(boundExchange.get()).isTrue();
            } finally {
                release.countDown();
            }
            response.get(2, TimeUnit.SECONDS);
            assertThat(contextCleared.get()).isTrue();
        } finally {
            release.countDown();
            eventLoop.shutdownGracefully(0, 2, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void boundedQueueRejectsThirdRequestWithoutAuthenticationOrDownstreamFallback() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var authentications = new AtomicInteger();
        var downstream = new AtomicInteger();
        try (var filter = filter(1, 1)) {
            filter.setAuth(ignored -> {
                if (authentications.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                }
            });
            WebFilterChain chain = authorized -> {
                downstream.incrementAndGet();
                return Mono.empty();
            };
            var first = filter.filter(exchange(), chain).toFuture();
            CompletableFuture<Void> second = null;
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                second = filter.filter(exchange(), chain).toFuture();
                var rejected = exchange();
                filter.filter(rejected, chain).block(Duration.ofSeconds(2));
                assertBusy(rejected);
                assertThat(authentications.get()).isEqualTo(1);
                assertThat(downstream.get()).isZero();
                assertThat(second.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(authentications.get()).isEqualTo(2);
            assertThat(downstream.get()).isEqualTo(2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void closedPoolFailsClosedWithoutInvokingAuthenticationOrChainAndCloseIsIdempotent() {
        var invoked = new AtomicBoolean();
        var filter = filter(1, 1);
        filter.setAuth(ignored -> invoked.set(true));
        filter.close();
        filter.close();
        var exchange = exchange();
        filter
            .filter(exchange, authorized -> {
                invoked.set(true);
                return Mono.empty();
            })
            .block(Duration.ofSeconds(2));
        assertBusy(exchange);
        assertThat(invoked.get()).isFalse();
    }

    @Test
    void asynchronousDownstreamRejectionIsNotConvertedIntoAuthenticationBusy() {
        try (var filter = filter(1, 4)) {
            var failure = new RejectedExecutionException("isolated downstream failure");
            var exchange = exchange();
            assertThatThrownBy(() ->
                filter.filter(exchange, authorized -> Mono.error(failure)).block(Duration.ofSeconds(2))
            ).isSameAs(failure);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void synchronousDownstreamAssemblyRejectionIsNotConvertedIntoAuthenticationBusy() {
        try (var filter = filter(1, 4)) {
            var failure = new RejectedExecutionException("isolated downstream assembly failure");
            var exchange = exchange();
            assertThatThrownBy(() ->
                filter
                    .filter(exchange, authorized -> {
                        throw failure;
                    })
                    .block(Duration.ofSeconds(2))
            ).isSameAs(failure);
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void authenticationErrorKeepsOfficialErrorHookAndClearsContextBeforeWorkerReuse() {
        try (var filter = filter(1, 4)) {
            var downstream = new AtomicBoolean();
            var exchange = exchange();
            filter.setAuth(ignored -> {
                throw new IllegalStateException("isolated private auth detail");
            });
            filter.setError(error -> {
                assertThat(SaReactorSyncHolder.getExchange()).isSameAs(exchange);
                SaHolder.getResponse().setStatus(403);
                return "forbidden";
            });
            filter
                .filter(exchange, authorized -> {
                    downstream.set(true);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(2));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("forbidden");
            assertThat(downstream.get()).isFalse();
            filter.setBeforeAuth(ignored -> assertThat(SaReactorSyncHolder.getExchange()).isNotSameAs(exchange));
            filter.setAuth(ignored -> {});
            filter
                .filter(exchange(), authorized -> {
                    assertThat(SaManager.getSaTokenContext().isValid()).isFalse();
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(2));
        }
    }

    @Test
    void invalidWorkerOrQueueLimitsAreRejectedBeforePoolAllocation() {
        for (int[] limits : new int[][] { { 0, 1 }, { 33, 1 }, { 1, 0 }, { 1, 257 } }) {
            assertThatThrownBy(() -> new OffloadedSaReactorFilter(limits[0], limits[1])).isInstanceOf(
                IllegalArgumentException.class
            );
        }
    }

    @Test
    void productionErrorHookReportsRedisFailureAsUnavailableWithoutGrantingOrLeakingDetails() {
        try (var filter = new SaTokenGatewayConfiguration().saReactorFilter()) {
            var downstream = new AtomicBoolean();
            filter.setBeforeAuth(ignored -> {
                throw new RedisConnectionFailureException("isolated redis password must never be exposed");
            });
            var exchange = exchange();
            filter
                .filter(exchange, authorized -> {
                    downstream.set(true);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(2));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exchange.getResponse().getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(2)))
                .contains("\"code\":\"AUTH_UNAVAILABLE\"")
                .doesNotContain("password", "isolated", "Exception");
            assertThat(downstream.get()).isFalse();
        }
    }

    @Test
    void privateAssetReferenceMetadataAndQuotaPathsRequireAuthentication() {
        String id = "61000000-0000-4000-8000-000000000001";
        try (var filter = new SaTokenGatewayConfiguration().saReactorFilter()) {
            for (String path : new String[] {
                "/api/assets/images/" + id + "/references",
                "/api/assets/images/" + id,
                "/api/assets/images/quota",
            }) {
                var downstream = new AtomicBoolean();
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                filter
                    .filter(exchange, authorized -> {
                        downstream.set(true);
                        return Mono.empty();
                    })
                    .block(Duration.ofSeconds(2));
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(exchange.getResponse().getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
                assertThat(downstream.get()).isFalse();
            }
        }
    }

    @Test
    void publicContentPathOnlySkipsLoginNotAssetServiceAuthorization() {
        try (var filter = new SaTokenGatewayConfiguration().saReactorFilter()) {
            var reached = new AtomicBoolean();
            var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/assets/images/61000000-0000-4000-8000-000000000001/content")
            );
            filter
                .filter(exchange, authorized -> {
                    reached.set(true);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(2));
            assertThat(reached.get()).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    private static OffloadedSaReactorFilter filter(int workers, int queue) {
        var filter = new OffloadedSaReactorFilter(workers, queue);
        filter.addInclude("/**");
        return filter;
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/operations/access"));
    }

    private static void assertBusy(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(2)))
            .contains("\"code\":\"AUTH_BUSY\"")
            .doesNotContain("isolated", "Exception");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("isolated authentication gate timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("isolated authentication gate interrupted", interrupted);
        }
    }
}
