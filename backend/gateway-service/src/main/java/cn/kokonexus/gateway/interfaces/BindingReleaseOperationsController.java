package cn.kokonexus.gateway.interfaces;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.operations.BindingAttemptPage;
import cn.kokonexus.api.operations.BindingAttemptView;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.BindingReleaseView;
import cn.kokonexus.gateway.infrastructure.BindingReleaseOperationsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 仅只读排障，身份来自当前 Sa-Token；下游再次检查专用权限，无任务重置/删除 API。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
@RequestMapping("/api/operations/binding-releases/{domain}")
@Tag(name = "资产绑定释放排障")
public class BindingReleaseOperationsController {

    /** 只有 identity/community 两个固定域的 RPC 适配。 */
    private final BindingReleaseOperationsClient client;

    @GetMapping("/attempts/open")
    @Operation(
        summary = "读取新协议OPEN凭据",
        description = "固定写域、升序游标。时间不是结束证明，不清除旧协议未知意图。"
    )
    public Mono<BindingAttemptPage> openAttempts(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Parameter(description = "exclusive创建时点，与afterRequestId成对，保留微秒") @RequestParam(
            required = false
        ) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime afterCreatedAt,
        @Parameter(description = "原请求UUID，与afterCreatedAt成对") @RequestParam(
            required = false
        ) String afterRequestId,
        @Parameter(description = "1～50") @RequestParam(defaultValue = "20") int limit,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, () -> {
            if (
                limit < 1 || limit > 50 || (afterCreatedAt == null) != (afterRequestId == null)
            ) throw new IllegalArgumentException("分页大小或成对游标无效");
            var cursor = afterCreatedAt == null ? null : new BindingReleaseCursor(afterCreatedAt, afterRequestId);
            return client.openAttempts(domain, StpUtil.getLoginIdAsString(), cursor, limit);
        });
    }

    @GetMapping("/attempts/{requestId}")
    @Operation(
        summary = "读取原绑定新协议凭据",
        description = "404只表示无凭据，不能推断旧意图回滚；ABORTED不是资产删除。"
    )
    public Mono<BindingAttemptView> attemptDetail(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Parameter(description = "原绑定UUID") @PathVariable String requestId,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, () -> client.attemptDetail(domain, StpUtil.getLoginIdAsString(), requestId));
    }

    @GetMapping("/dead")
    @Operation(
        summary = "读取绑定释放 DEAD 游标页",
        description = "专用读权限；并发变化需刷新，不返回领取秘密或原始错误。"
    )
    public Mono<BindingReleasePage> dead(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Parameter(description = "exclusive 创建时间，与 beforeRequestId 成对") @RequestParam(
            required = false
        ) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeCreatedAt,
        @Parameter(description = "原绑定请求 UUID") @RequestParam(required = false) String beforeRequestId,
        @Parameter(description = "1～50") @RequestParam(defaultValue = "20") int limit,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, () -> {
            if (limit < 1 || limit > 50 || (beforeCreatedAt == null) != (beforeRequestId == null)) {
                throw new IllegalArgumentException("分页大小或成对游标无效");
            }
            var cursor = beforeCreatedAt == null ? null : new BindingReleaseCursor(beforeCreatedAt, beforeRequestId);
            return client.dead(domain, StpUtil.getLoginIdAsString(), cursor, limit);
        });
    }

    @GetMapping("/tasks/{requestId}")
    @Operation(summary = "读取原绑定请求释放事实", description = "SENT 不代表资产被删除，DEAD 不允许清除未知意图。")
    public Mono<BindingReleaseView> detail(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Parameter(description = "原绑定请求 UUID") @PathVariable String requestId,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, () -> client.detail(domain, StpUtil.getLoginIdAsString(), requestId));
    }

    @GetMapping("/snapshot")
    @Operation(
        summary = "读取当前业务库有界状态采样",
        description = "同一 SQL 时点；每状态最多 1001，达到上限即为下界，不是全站精确总量。"
    )
    public Mono<BindingReleaseSnapshot> snapshot(
        @Parameter(description = "identity/community") @PathVariable String domain,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, () -> client.snapshot(domain, StpUtil.getLoginIdAsString()));
    }

    private static <T> Mono<T> authorized(ServerWebExchange exchange, Supplier<T> action) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.checkLogin();
                StpUtil.checkPermission("asset:binding:read");
                return action.get();
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }
}
