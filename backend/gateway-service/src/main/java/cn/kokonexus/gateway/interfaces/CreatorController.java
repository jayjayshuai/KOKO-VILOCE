package cn.kokonexus.gateway.interfaces;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.identity.CreatorFollowState;
import cn.kokonexus.api.identity.CreatorPage;
import cn.kokonexus.api.identity.CreatorProfile;
import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.identity.SaveCreatorProfileCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** gateway-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@Validated
@io.swagger.v3.oas.annotations.tags.Tag(name = "CreatorController")
@RestController
public class CreatorController {

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 5000, retries = 0)
    private IdentityRpcService identityRpcService;

    @GetMapping("/api/discovery/creators")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取公开创作者")
    public Mono<List<CreatorProfile>> discover(@RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return Mono.fromCallable(() -> identityRpcService.listPublishedCreators(limit)).subscribeOn(
            Schedulers.boundedElastic()
        );
    }

    @GetMapping("/api/discovery/creators/page")
    @io.swagger.v3.oas.annotations.Operation(summary = "分页读取公开创作者")
    public Mono<CreatorPage> discoverPage(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size
    ) {
        return Mono.fromCallable(() -> identityRpcService.pagePublishedCreators(page, size)).subscribeOn(
            Schedulers.boundedElastic()
        );
    }

    @GetMapping("/api/discovery/creators/{slug}")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取已发布主页")
    public Mono<CreatorProfile> published(@PathVariable String slug) {
        return Mono.fromCallable(() -> identityRpcService.findPublishedCreator(slug))
            .subscribeOn(Schedulers.boundedElastic())
            .switchIfEmpty(Mono.error(new GatewayResourceNotFoundException("创作者主页不存在")));
    }

    @GetMapping("/api/creators/me")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取本人资源")
    public Mono<CreatorProfile> mine(ServerWebExchange exchange) {
        return authenticated(exchange, () ->
            identityRpcService.findCreatorProfile(StpUtil.getLoginIdAsString())
        ).switchIfEmpty(Mono.error(new GatewayResourceNotFoundException("尚未创建创作者主页")));
    }

    @GetMapping("/api/creators/following")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取本人关注")
    public Mono<CreatorPage> following(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size,
        ServerWebExchange exchange
    ) {
        return authenticated(exchange, () ->
            identityRpcService.pageFollowedCreators(StpUtil.getLoginIdAsString(), page, size)
        );
    }

    @GetMapping("/api/creators/{creatorId}/follow")
    @io.swagger.v3.oas.annotations.Operation(summary = "读取关注状态")
    public Mono<CreatorFollowState> followState(@PathVariable String creatorId, ServerWebExchange exchange) {
        return authenticated(exchange, () ->
            identityRpcService.creatorFollowState(StpUtil.getLoginIdAsString(), creatorId)
        );
    }

    @PutMapping("/api/creators/{creatorId}/follow")
    @io.swagger.v3.oas.annotations.Operation(summary = "关注创作者")
    public Mono<CreatorFollowState> follow(@PathVariable String creatorId, ServerWebExchange exchange) {
        return authenticated(exchange, () -> identityRpcService.followCreator(StpUtil.getLoginIdAsString(), creatorId));
    }

    @DeleteMapping("/api/creators/{creatorId}/follow")
    @io.swagger.v3.oas.annotations.Operation(summary = "取消关注")
    public Mono<CreatorFollowState> unfollow(@PathVariable String creatorId, ServerWebExchange exchange) {
        return authenticated(exchange, () ->
            identityRpcService.unfollowCreator(StpUtil.getLoginIdAsString(), creatorId)
        );
    }

    @PutMapping("/api/creators/me")
    @io.swagger.v3.oas.annotations.Operation(summary = "保存本人业务配置")
    public Mono<CreatorProfile> save(
        @Valid @RequestBody SaveCreatorProfileRequest request,
        ServerWebExchange exchange
    ) {
        return authenticated(exchange, () ->
            identityRpcService.saveCreatorProfile(
                StpUtil.getLoginIdAsString(),
                new SaveCreatorProfileCommand(
                    request.slug(),
                    request.displayName(),
                    request.headline(),
                    request.bio(),
                    request.avatarUrl(),
                    request.bannerUrl(),
                    request.avatarAssetId(),
                    request.bannerAssetId(),
                    request.version()
                )
            )
        );
    }

    @PostMapping("/api/creators/me/publish")
    @io.swagger.v3.oas.annotations.Operation(summary = "发布本人资源")
    public Mono<CreatorProfile> publish(@RequestParam @Min(0) long version, ServerWebExchange exchange) {
        return authenticated(exchange, () ->
            identityRpcService.publishCreatorProfile(StpUtil.getLoginIdAsString(), version)
        );
    }

    private <T> Mono<T> authenticated(ServerWebExchange exchange, java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(() ->
            SaReactorSyncHolder.setContext(exchange, () -> {
                try {
                    return operation.call();
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalStateException("创作者服务调用失败", exception);
                }
            })
        ).subscribeOn(Schedulers.boundedElastic());
    }

    /** gateway-service：请求契约；字段校验以公开接口约束为准。 */
    public record SaveCreatorProfileRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识")
        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")
        String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "用户公开显示名称")
        @NotBlank
        @Size(max = 80)
        String displayName,
        @io.swagger.v3.oas.annotations.media.Schema(description = "创作者主页短介绍") @Size(max = 120) String headline,
        @io.swagger.v3.oas.annotations.media.Schema(description = "创作者个人简介") @Size(max = 1000) String bio,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开头像地址") @Size(max = 1000) String avatarUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开横幅地址") @Size(max = 1000) String bannerUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的头像资产 UUID") String avatarAssetId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的横幅资产 UUID") String bannerAssetId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值") @Min(0) long version
    ) {}
}
