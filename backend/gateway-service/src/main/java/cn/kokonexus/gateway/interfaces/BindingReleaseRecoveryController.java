package cn.kokonexus.gateway.interfaces;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.gateway.infrastructure.BindingReleaseRecoveryClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** 当前会话和固定资产动作，业务域再次授权；秘密只能 POST body 输入且不得记录。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
@RequestMapping("/api/operations/binding-releases/{domain}")
@Tag(name = "资产绑定人工恢复与审计")
public class BindingReleaseRecoveryController {

    /** 固定提交域与独立身份确认。 */
    private final BindingReleaseRecoveryClient client;

    @PostMapping("/confirmations")
    @Operation(
        summary = "本人密码确认单个资产恢复命令",
        description = "五分钟、当前会话/权限/密码版本与完整命令绑定；不受理排队。"
    )
    public Mono<ReplayConfirmation> confirm(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Valid @RequestBody ConfirmationRequest request,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "asset:binding:replay", () ->
            client.confirm(domain, StpUtil.getLoginIdAsString(), sessionHash(), request.command(), request.password())
        );
    }

    @PostMapping("/replays")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "同事务重新排队并追加审计，返回202",
        description = "只受理DEAD，不清零累计次数，不同步完成保护释放或删除对象；超时保留原人工命令。"
    )
    public Mono<BindingReleaseReplayReceipt> replay(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Valid @RequestBody RecoveryRequest request,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "asset:binding:replay", () ->
            client.replay(
                domain,
                StpUtil.getLoginIdAsString(),
                sessionHash(),
                request.command(),
                request.confirmationToken()
            )
        );
    }

    @GetMapping("/tasks/{requestId}/commands/{commandId}")
    @Operation(summary = "查询原人工命令受理事实", description = "404只表示本次未观察到，不能丢弃不确定幂等键。")
    public Mono<BindingReleaseAuditView> receipt(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Parameter(description = "原绑定UUID") @PathVariable String requestId,
        @Parameter(description = "原人工命令UUID") @PathVariable String commandId,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "asset:binding:read", () ->
            client.receipt(domain, StpUtil.getLoginIdAsString(), requestId, commandId)
        );
    }

    @GetMapping("/tasks/{requestId}/audits")
    @Operation(
        summary = "读取只追加的人工恢复审计",
        description = "代次倒序、exclusive分页，不含密码、确认秘密或领取令牌。"
    )
    public Mono<BindingReleaseAuditPage> audits(
        @Parameter(description = "identity/community") @PathVariable String domain,
        @Parameter(description = "原绑定UUID") @PathVariable String requestId,
        @Parameter(description = "exclusive代次1～11") @RequestParam(required = false) Integer beforeGeneration,
        @Parameter(description = "1～20") @RequestParam(defaultValue = "5") int limit,
        ServerWebExchange exchange
    ) {
        return authorized(exchange, "asset:binding:read", () -> {
            if (
                limit < 1 || limit > 20 || (beforeGeneration != null && (beforeGeneration < 1 || beforeGeneration > 11))
            ) throw new IllegalArgumentException("审计分页无效");
            return client.audits(domain, StpUtil.getLoginIdAsString(), requestId, beforeGeneration, limit);
        });
    }

    private static String sessionHash() {
        String token = StpUtil.getTokenValue();
        if (token == null || token.isBlank()) throw new OperationsAccessDeniedException("当前会话不可绑定");
        return ReplayCommandBinding.sha256(token);
    }

    private static <T> Mono<T> authorized(ServerWebExchange exchange, String permission, Supplier<T> operation) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.checkLogin();
                StpUtil.checkPermission(permission);
                return operation.get();
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    /** 密码只输入，不打印整条命令或原因。 */
    public record ConfirmationRequest(
        @Schema(description = "待确认的资产恢复命令") @NotNull BindingReleaseReplayCommand command,
        @Schema(description = "本人密码，仅输入禁止日志", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank
        @Size(max = 72)
        String password
    ) {
        @Override
        public String toString() {
            return "BindingConfirmationRequest[password=<redacted>]";
        }
    }

    /** 随机确认秘密只用于已确认的原命令，不持久化浏览器。 */
    public record RecoveryRequest(
        @Schema(description = "已经确认且完全相同的原命令") @NotNull BindingReleaseReplayCommand command,
        @Schema(description = "资产动作确认秘密，仅输入禁止日志", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotNull
        @Pattern(regexp = "[A-Za-z0-9_-]{43}")
        String confirmationToken
    ) {
        @Override
        public String toString() {
            return "BindingRecoveryRequest[token=<redacted>]";
        }
    }
}
