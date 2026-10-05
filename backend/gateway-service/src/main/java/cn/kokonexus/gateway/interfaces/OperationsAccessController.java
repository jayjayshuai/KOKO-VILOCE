package cn.kokonexus.gateway.interfaces;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.gateway.infrastructure.OperationsAuthorizationClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 运营能力独立命名空间；身份与会话只从 Sa-Token 认证上下文取得，不读取伪造用户头。 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
@RequestMapping("/api/operations")
@Tag(name = "运营权限与二次确认")
public class OperationsAccessController {

    /** 授权与本人密码确认 RPC 适配器。 */
    private final OperationsAuthorizationClient client;

    @GetMapping("/access")
    @Operation(summary = "读取本人当前运营能力，不暴露他人角色")
    public Mono<OperationsAccess> access(ServerWebExchange exchange) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.checkLogin();
                return client.access(StpUtil.getLoginIdAsString());
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/replay-confirmations")
    @Operation(
        summary = "本人密码确认绑定单条重放命令，错误返回 403、限速返回 429",
        description = "不受理重放或直接发送消息。秘密凭据五分钟有效、绑定当前会话；禁止日志/浏览器持久存储。"
    )
    public Mono<ReplayConfirmation> confirmation(
        @Valid @RequestBody ConfirmationRequest request,
        ServerWebExchange exchange
    ) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.checkLogin();
                StpUtil.checkPermission("notification:outbox:replay");
                String currentToken = StpUtil.getTokenValue();
                if (currentToken == null || currentToken.isBlank()) throw new OperationsAccessDeniedException(
                    "会话绑定不可用"
                );
                String sessionHash = ReplayCommandBinding.sha256(currentToken);
                return client.confirmReplay(
                    StpUtil.getLoginIdAsString(),
                    sessionHash,
                    request.domain(),
                    request.command(),
                    request.password()
                );
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/role-changes")
    @Operation(
        summary = "管理员本人密码确认后赋权或撤权，不能修改本人角色",
        description = "服务端读取真实操作者；关系与账号版本、追加审计同事务提交。相同命令可幂等重试，仍须当前权限和密码。"
    )
    public Mono<OperationsRoleChangeReceipt> changeRole(
        @Valid @RequestBody RoleChangeRequest request,
        ServerWebExchange exchange
    ) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.checkLogin();
                StpUtil.checkPermission("operations:roles:manage");
                return client.changeRole(StpUtil.getLoginIdAsString(), request.command(), request.password());
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    /** 角色命令中的目标账号不代表操作者，密码不进入 DTO 调试输出。 */
    public record RoleChangeRequest(
        @Schema(description = "固定角色、目标账号和预期关系版本，操作者由认证上下文提供")
        @NotNull
        OperationsRoleChangeCommand command,
        @Schema(
            description = "管理员本人密码，仅输入，禁止日志与浏览器持久化",
            accessMode = Schema.AccessMode.WRITE_ONLY
        )
        @NotBlank
        @Size(max = 72)
        String password
    ) {
        @Override
        public String toString() {
            return "RoleChangeRequest[password=<redacted>]";
        }
    }

    /** 密码仅本次本人确认使用，重写 toString 避免异常/诊断意外输出秘密。 */
    public record ConfirmationRequest(
        @Schema(description = "固定域 identity/community/live")
        @Pattern(regexp = "identity|community|live")
        @NotBlank
        String domain,
        @Schema(description = "需要二次确认的整条命令，修改任何字段会使凭据失效") @NotNull OutboxReplayCommand command,
        @Schema(description = "本人密码，仅输入，禁止日志与浏览器持久化", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank
        @Size(max = 72)
        String password
    ) {
        @Override
        public String toString() {
            return "ConfirmationRequest[domain=" + domain + ", password=<redacted>]";
        }
    }
}
