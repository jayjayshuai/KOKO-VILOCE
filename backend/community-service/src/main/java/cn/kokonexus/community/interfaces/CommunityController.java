package cn.kokonexus.community.interfaces;

import cn.kokonexus.community.application.CommunityApplicationService;
import cn.kokonexus.community.domain.Community;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** community-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@RestController
@RequestMapping("/api")
@Tag(name = "社区")
public class CommunityController {

    /** CommunityApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final CommunityApplicationService applicationService;

    public CommunityController(CommunityApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/communities")
    @Operation(summary = "创建社区", description = "创建者自动成为社区所有者；slug 在全平台唯一。")
    public CommunityView create(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody CreateCommunityRequest request
    ) {
        return CommunityView.from(
            applicationService.create(userId, request.slug(), request.name(), request.description(), request.badge())
        );
    }

    @GetMapping("/discovery/communities")
    @Operation(summary = "发现公开社区")
    public List<CommunityView> discover(@RequestParam(defaultValue = "12") int limit) {
        return applicationService.discover(limit).stream().map(CommunityView::from).toList();
    }

    @GetMapping("/communities/mine")
    @Operation(summary = "查询本人管理的社区")
    public List<CommunityView> mine(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId
    ) {
        return applicationService.ownedBy(userId).stream().map(CommunityView::from).toList();
    }

    @PutMapping("/communities/{communityId}")
    @Operation(summary = "修改社区", description = "仅所有者可操作，并使用版本号防止并发覆盖。")
    public CommunityView update(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long communityId,
        @Valid @RequestBody UpdateCommunityRequest request
    ) {
        return CommunityView.from(
            applicationService.update(
                userId,
                communityId,
                request.version(),
                request.name(),
                request.description(),
                request.badge(),
                request.visibility()
            )
        );
    }

    @DeleteMapping("/communities/{communityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "归档社区", description = "社区进入不可逆归档终态，不物理删除成员和审计数据。")
    public void archive(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long communityId,
        @RequestParam @PositiveOrZero long version
    ) {
        applicationService.archive(userId, communityId, version);
    }

    /** community-service：请求契约；字段校验以公开接口约束为准。 */
    public record CreateCommunityRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识")
        @Pattern(regexp = "[a-z0-9-]{3,64}")
        String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务名称") @NotBlank @Size(max = 80) String name,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务说明") @Size(max = 500) String description,
        @io.swagger.v3.oas.annotations.media.Schema(description = "社区短徽标")
        @Pattern(regexp = "[a-zA-Z0-9]{1,8}")
        String badge
    ) {}

    /** community-service：请求契约；字段校验以公开接口约束为准。 */
    public record UpdateCommunityRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务名称") @NotBlank @Size(max = 80) String name,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务说明") @Size(max = 500) String description,
        @io.swagger.v3.oas.annotations.media.Schema(description = "社区短徽标")
        @Pattern(regexp = "[a-zA-Z0-9]{1,8}")
        String badge,
        @io.swagger.v3.oas.annotations.media.Schema(description = "资源可见范围，PUBLIC 或 PRIVATE")
        @Pattern(regexp = "PUBLIC|PRIVATE")
        String visibility,
        @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值")
        @PositiveOrZero
        long version
    ) {}

    /** community-service：CommunityView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record CommunityView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识") String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务名称") String name,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务说明") String description,
        @io.swagger.v3.oas.annotations.media.Schema(description = "社区短徽标") String badge,
        @io.swagger.v3.oas.annotations.media.Schema(description = "会话当前成员投影列表") long members,
        @io.swagger.v3.oas.annotations.media.Schema(description = "资源可见范围，PUBLIC 或 PRIVATE") String visibility,
        @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值") long version
    ) {
        static CommunityView from(Community community) {
            return new CommunityView(
                String.valueOf(community.getId()),
                community.getSlug(),
                community.getName(),
                community.getDescription(),
                community.getBadge(),
                community.getMemberCount(),
                community.getVisibility(),
                community.getVersion()
            );
        }
    }
}
