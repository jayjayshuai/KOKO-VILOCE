package cn.kokonexus.voice.interfaces;

import cn.kokonexus.voice.application.VoiceApplicationService;
import cn.kokonexus.voice.domain.VoiceRoom;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** voice-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@RestController
@RequestMapping("/api/voice/rooms")
@Tag(name = "语音房")
public class VoiceController {

    /** VoiceApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final VoiceApplicationService applicationService;

    public VoiceController(VoiceApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    @Operation(summary = "创建真实 LiveKit 语音房")
    public VoiceRoomView create(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody CreateVoiceRoomRequest request
    ) {
        return VoiceRoomView.from(
            applicationService.create(
                userId,
                request.slug(),
                request.title(),
                request.topic(),
                request.maxParticipants()
            )
        );
    }

    @GetMapping("/discovery")
    @Operation(summary = "发现开放语音房")
    public List<VoiceRoomView> discover(@RequestParam(defaultValue = "12") int limit) {
        return applicationService.discover(limit).stream().map(VoiceRoomView::from).toList();
    }

    @PostMapping("/{roomId}/join")
    @Operation(summary = "签发十分钟有效的 LiveKit 入会凭证")
    public VoiceApplicationService.JoinCredential join(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long roomId
    ) {
        return applicationService.join(userId, roomId);
    }

    @DeleteMapping("/{roomId}")
    @Operation(summary = "房主关闭语音房")
    public void close(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long roomId
    ) {
        applicationService.close(userId, roomId);
    }

    /** voice-service：请求契约；字段校验以公开接口约束为准。 */
    public record CreateVoiceRoomRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识")
        @Pattern(regexp = "[a-z0-9-]{3,80}")
        String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") @NotBlank @Size(max = 120) String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "主题或消息队列 Topic，具体见所属类型")
        @Size(max = 300)
        String topic,
        @io.swagger.v3.oas.annotations.media.Schema(description = "房间人数上限") @Min(2) @Max(100) int maxParticipants
    ) {}

    /** voice-service：VoiceRoomView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record VoiceRoomView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识") String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "主题或消息队列 Topic，具体见所属类型") String topic,
        @io.swagger.v3.oas.annotations.media.Schema(description = "资源所有者公开名称") String owner,
        @io.swagger.v3.oas.annotations.media.Schema(description = "房间人数上限") int maxParticipants,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务状态，允许值以所属领域状态机为准") String status
    ) {
        static VoiceRoomView from(VoiceRoom room) {
            return new VoiceRoomView(
                String.valueOf(room.getId()),
                room.getSlug(),
                room.getTitle(),
                room.getTopic(),
                room.getOwnerName(),
                room.getMaxParticipants(),
                room.getStatus()
            );
        }
    }
}
