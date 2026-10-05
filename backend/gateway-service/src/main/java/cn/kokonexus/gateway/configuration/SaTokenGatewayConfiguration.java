package cn.kokonexus.gateway.configuration;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.gateway.filter.OffloadedSaReactorFilter;
import cn.kokonexus.gateway.filter.TrustedUserHeaderFilter;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** gateway-service：服务配置；不在源码内保存生产密钥。 */
@Configuration
public class SaTokenGatewayConfiguration {

    /** 服务端格式校验规则，禁止客户端覆盖。 */
    private static final Pattern PUBLIC_IMAGE = Pattern.compile(
        "^/api/assets/images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/content$"
    );

    /** 单实例同步鉴权线程数；不随请求创建，不代表生产容量认证。 */
    @Value("${koko.gateway.authentication.workers:4}")
    private int authenticationWorkers = 4;

    /** 每工作线程最多等待任务数，超限拒绝请求，不退回 Netty 执行。 */
    @Value("${koko.gateway.authentication.queued-tasks-per-thread:64}")
    private int queuedTasksPerThread = 64;

    @Bean(destroyMethod = "close")
    public OffloadedSaReactorFilter saReactorFilter() {
        var filter = new OffloadedSaReactorFilter(authenticationWorkers, queuedTasksPerThread);
        filter
            .addInclude("/**")
            .setAuth(object -> {
                SaRouter.match("/api/auth/register", () -> {});
                SaRouter.match("/api/auth/login", () -> {});
                SaRouter.match("/api/discovery/**", () -> {});
                SaRouter.match("/api/live/discovery", () -> {});
                SaRouter.match("/api/voice/rooms/discovery", () -> {});
                SaRouter.match("/actuator/health/**", () -> {});
                SaRouter.match("/api/**")
                    .notMatch(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/discovery/**",
                        "/api/live/discovery",
                        "/api/voice/rooms/discovery"
                    )
                    .check(() -> {
                        var exchange = SaReactorSyncHolder.getExchange();
                        boolean publicImage =
                            exchange.getRequest().getMethod() == HttpMethod.GET &&
                            PUBLIC_IMAGE.matcher(exchange.getRequest().getURI().getPath()).matches();
                        if (!publicImage) StpUtil.checkLogin();
                    });
                if (StpUtil.isLogin()) {
                    SaReactorSyncHolder.getExchange()
                        .getAttributes()
                        .put(TrustedUserHeaderFilter.LOGIN_ID_ATTRIBUTE, StpUtil.getLoginIdAsString());
                }
            })
            .setError(error -> {
                boolean notLoggedIn = error instanceof NotLoginException;
                boolean denied = error instanceof NotPermissionException || error instanceof NotRoleException;
                int status = notLoggedIn ? 401 : denied ? 403 : 503;
                SaHolder.getResponse().setStatus(status);
                SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=UTF-8");
                SaHolder.getResponse().setHeader("Cache-Control", "no-store");
                if (status == 503) {
                    SaHolder.getResponse().setHeader("Retry-After", "1");
                    return "{\"code\":\"AUTH_UNAVAILABLE\",\"message\":\"鉴权服务暂时不可用，请稍后重试\"}";
                }
                return notLoggedIn
                    ? "{\"code\":\"AUTH_REQUIRED\",\"message\":\"请先登录\"}"
                    : "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"无权访问\"}";
            });
        return filter;
    }
}
