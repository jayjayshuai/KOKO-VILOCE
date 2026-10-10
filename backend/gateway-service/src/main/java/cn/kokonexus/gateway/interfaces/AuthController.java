package cn.kokonexus.gateway.interfaces;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.identity.AuthenticateIdentityCommand;
import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.identity.RegisterIdentityCommand;
import cn.kokonexus.api.identity.UserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** gateway-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "AuthController")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 10000, retries = 0)
    private IdentityRpcService identityRpcService;

    @PostMapping("/register")
    @io.swagger.v3.oas.annotations.Operation(summary = "注册账号")
    public Mono<AuthResponse> register(@Valid @RequestBody RegisterRequest request, ServerWebExchange exchange) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () ->
                establishLogin(
                    identityRpcService.register(
                        new RegisterIdentityCommand(
                            request.email(),
                            request.password(),
                            request.handle(),
                            request.displayName()
                        )
                    )
                )
            )
        ).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/login")
    @io.swagger.v3.oas.annotations.Operation(summary = "账号登录")
    public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest request, ServerWebExchange exchange) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () ->
                establishLogin(
                    identityRpcService.authenticate(
                        new AuthenticateIdentityCommand(request.email(), request.password())
                    )
                )
            )
        )
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(IllegalArgumentException.class, ignored -> new AuthenticationFailedException());
    }

    @PostMapping("/logout")
    @io.swagger.v3.oas.annotations.Operation(summary = "注销当前会话")
    public Mono<Void> logout(ServerWebExchange exchange) {
        return Mono.fromRunnable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                StpUtil.logout();
                return null;
            })
        )
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @GetMapping("/me")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取本人账号")
    public Mono<UserIdentity> me(ServerWebExchange exchange) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () ->
                identityRpcService.findActiveUser(StpUtil.getLoginIdAsString())
            )
        ).subscribeOn(Schedulers.boundedElastic());
    }

    private AuthResponse establishLogin(UserIdentity user) {
        StpUtil.login(user.id());
        return new AuthResponse(StpUtil.getTokenName(), StpUtil.getTokenValue(), StpUtil.getTokenTimeout(), user);
    }

    /** gateway-service：请求契约；字段校验以公开接口约束为准。 */
    public record RegisterRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "登录邮箱，个人敏感信息")
        @Email
        @NotBlank
        String email,
        @io.swagger.v3.oas.annotations.media.Schema(description = "登录密码，仅输入使用，禁止日志输出")
        @Size(min = 10, max = 72)
        @NotBlank
        String password,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开用户名，用于精确查询")
        @Pattern(regexp = "[a-zA-Z0-9_]{3,32}")
        @NotBlank
        String handle,
        @io.swagger.v3.oas.annotations.media.Schema(description = "用户公开显示名称")
        @NotBlank
        @Size(max = 80)
        String displayName
    ) {
        /** record默认文本会泄漏密码/邮箱，序列化与访问器保持原契约。 */
        @Override
        public String toString() {
            return "RegisterRequest[redacted]";
        }
    }

    /** gateway-service：请求契约；字段校验以公开接口约束为准。 */
    public record LoginRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "登录邮箱，个人敏感信息")
        @Email
        @NotBlank
        String email,
        @io.swagger.v3.oas.annotations.media.Schema(description = "登录密码，仅输入使用，禁止日志输出")
        @NotBlank
        String password
    ) {
        /** 登录输入不得出现在框架或诊断日志的对象文本中。 */
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    /** gateway-service：AuthResponse 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record AuthResponse(
        @io.swagger.v3.oas.annotations.media.Schema(description = "会话令牌的 Cookie/请求头名称") String tokenName,
        @io.swagger.v3.oas.annotations.media.Schema(description = "会话令牌，仅认证响应；禁止写日志或本地存储")
        String tokenValue,
        @io.swagger.v3.oas.annotations.media.Schema(description = "令牌有效期，秒") long expiresIn,
        @io.swagger.v3.oas.annotations.media.Schema(description = "用户公开响应投影") UserIdentity user
    ) {
        /** 会话令牌只交给认证响应，禁止对象文本复制到日志。 */
        @Override
        public String toString() {
            return "AuthResponse[redacted]";
        }
    }
}
