package cn.kokonexus.gateway.interfaces;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.gateway.infrastructure.OutboxOperationsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sa-Token 当前权限与本人会话绑定；下游仍再次检查，不信任客户端身份头。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
@RequestMapping("/api/operations/outbox/{domain}")
@Tag(name = "通知死信与重放审计")
public class OutboxOperationsController {

    /** 三个固定事件域的 RPC 适配。 */
    private final OutboxOperationsClient client;

    @GetMapping("/dead")
    @Operation(
        summary = "读取本域 DEAD 游标页",
        description = "创建时间和事件 UUID 降序；并发状态变化需刷新，不是跨页快照。"
    )
    public Mono<OutboxDeadPage> dead(
        @Parameter(description = "identity/community/live 固定域") @PathVariable String domain,
        @Parameter(description = "exclusive 业务库创建时间，须与 beforeEventId 同时提供") @RequestParam(
            required = false
        ) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeCreatedAt,
        @Parameter(description = "exclusive 事件 UUID") @RequestParam(required = false) String beforeEventId,
        @Parameter(description = "1～50，每页最多五十条") @RequestParam(defaultValue = "20") int limit,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "notification:outbox:read", () -> {
            requireLimit(limit, 50);
            if ((beforeCreatedAt == null) != (beforeEventId == null)) {
                throw new IllegalArgumentException("须同时提供游标时间和事件标识");
            }
            var cursor = beforeCreatedAt == null ? null : new OutboxEventCursor(beforeCreatedAt, beforeEventId);
            return client.dead(domain, StpUtil.getLoginIdAsString(), cursor, limit);
        });
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "读取事件当前投递事实", description = "可查重放后 PENDING/SENT；发送确认仍不代表消费完成。")
    public Mono<OutboxEventView> detail(
        @Parameter(description = "identity/community/live") @PathVariable String domain,
        @Parameter(description = "原事件 UUID") @PathVariable String eventId,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "notification:outbox:read", () ->
            client.detail(domain, StpUtil.getLoginIdAsString(), eventId)
        );
    }

    @GetMapping("/events/{eventId}/audits")
    @Operation(summary = "读取当前事件追加审计", description = "按已受理代次降序，exclusive 游标；无修改或删除 API。")
    public Mono<OutboxAuditPage> audits(
        @Parameter(description = "identity/community/live") @PathVariable String domain,
        @Parameter(description = "原事件 UUID") @PathVariable String eventId,
        @Parameter(description = "exclusive 代次，1～11") @RequestParam(required = false) Long beforeGeneration,
        @Parameter(description = "1～20") @RequestParam(defaultValue = "10") int limit,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "notification:outbox:read", () -> {
            requireLimit(limit, 20);
            if (beforeGeneration != null && (beforeGeneration < 1 || beforeGeneration > 11)) {
                throw new IllegalArgumentException("审计代次游标无效");
            }
            return client.audits(domain, StpUtil.getLoginIdAsString(), eventId, beforeGeneration, limit);
        });
    }

    @PostMapping("/replays")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "绑定二次确认后受理重放，返回 202 而非虚构送达",
        description = "必须保留原 requestId/事件/代次/原因重试；确认五分钟到期后须重新确认同一命令。"
    )
    public Mono<OutboxReplayReceipt> replay(
        @Parameter(description = "identity/community/live") @PathVariable String domain,
        @Valid @RequestBody ReplayRequest request,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "notification:outbox:replay", () -> {
            String token = StpUtil.getTokenValue();
            if (token == null || token.isBlank()) throw new OperationsAccessDeniedException("会话绑定不可用");
            return client.replay(
                domain,
                StpUtil.getLoginIdAsString(),
                ReplayCommandBinding.sha256(token),
                request.command(),
                request.confirmationToken()
            );
        });
    }

    @GetMapping("/events/{eventId}/requests/{requestId}")
    @Operation(summary = "只读查询原请求受理事实", description = "404 不是在途提交已终止的保证；保留原命令供幂等重试。")
    public Mono<OutboxAuditView> receipt(
        @Parameter(description = "identity/community/live") @PathVariable String domain,
        @Parameter(description = "原事件 UUID") @PathVariable String eventId,
        @Parameter(description = "原受理请求 UUID") @PathVariable String requestId,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "notification:outbox:read", () ->
            client.receipt(domain, StpUtil.getLoginIdAsString(), eventId, requestId)
        );
    }

    private static void requireLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw new IllegalArgumentException("分页大小超出允许范围");
    }

    private static <T> Mono<T> authorized(ServerWebExchange exchange, String permission, Supplier<T> action) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.checkLogin();
                StpUtil.checkPermission(permission);
                return action.get();
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    /** 确认秘密仅本次传输，诊断输出不含秘密或原因正文。 */
    public record ReplayRequest(
        @Schema(description = "已确认的完整命令，不得修改任一字段") @NotNull OutboxReplayCommand command,
        @Schema(description = "原随机确认秘密，仅输入，禁止日志或持久化", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotNull
        @Pattern(regexp = "[A-Za-z0-9_-]{43}")
        String confirmationToken
    ) {
        @Override
        public String toString() {
            return "ReplayRequest[confirmationToken=<redacted>]";
        }
    }
}
