package cn.kokonexus.live.interfaces;

import cn.kokonexus.live.application.LiveApplicationService;
import cn.kokonexus.live.domain.LiveStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** live-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@RestController
@RequestMapping("/api/live")
@Tag(name = "直播")
public class LiveController {

    /** LiveApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final LiveApplicationService applicationService;

    public LiveController(LiveApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    @Operation(summary = "创建直播", description = "创建待开播记录；未配置媒体供应商时不会伪造推流地址。")
    public LiveView create(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody CreateLiveRequest request
    ) {
        return LiveView.from(
            applicationService.create(
                userId,
                request.slug(),
                request.title(),
                request.category(),
                request.interactive()
            )
        );
    }

    @PatchMapping("/{streamId}/status")
    @Operation(summary = "变更直播状态", description = "仅创建者可按待开播、直播中、已结束的顺序迁移状态。")
    public void transition(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long streamId,
        @Valid @RequestBody TransitionRequest request
    ) {
        applicationService.transition(userId, streamId, request.status());
    }

    @GetMapping("/discovery")
    @Operation(summary = "发现直播中的房间")
    public List<LiveView> discover(@RequestParam(defaultValue = "12") int limit) {
        return applicationService.discover(limit).stream().map(LiveView::from).toList();
    }

    /** live-service：请求契约；字段校验以公开接口约束为准。 */
    public record CreateLiveRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识")
        @Pattern(regexp = "[a-z0-9-]{3,80}")
        String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") @NotBlank @Size(max = 120) String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "内容分类") @NotBlank @Size(max = 60) String category,
        @io.swagger.v3.oas.annotations.media.Schema(description = "是否允许互动连麦") boolean interactive
    ) {}

    /** live-service：请求契约；字段校验以公开接口约束为准。 */
    public record TransitionRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务状态，允许值以所属领域状态机为准")
        @Pattern(regexp = "LIVE|ENDED")
        String status
    ) {}

    /** live-service：LiveView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record LiveView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识") String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "创作者公开显示名称") String creator,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前观看人数") long viewers,
        @io.swagger.v3.oas.annotations.media.Schema(description = "内容分类") String category,
        @io.swagger.v3.oas.annotations.media.Schema(description = "是否允许互动连麦") boolean interactive,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务状态，允许值以所属领域状态机为准") String status
    ) {
        static LiveView from(LiveStream stream) {
            return new LiveView(
                String.valueOf(stream.getId()),
                stream.getSlug(),
                stream.getTitle(),
                stream.getCreatorName(),
                stream.getViewerCount(),
                stream.getCategory(),
                Boolean.TRUE.equals(stream.getInteractive()),
                stream.getStatus()
            );
        }
    }
}
