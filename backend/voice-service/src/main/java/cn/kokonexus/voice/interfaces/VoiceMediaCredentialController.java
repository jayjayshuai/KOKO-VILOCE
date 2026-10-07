package cn.kokonexus.voice.interfaces;

import cn.kokonexus.voice.application.VoiceMediaCredentialIssuer;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 独立于原LEGACY入会接口，当前会话/房间版本请求不能被旧结果回填。 */
@RestController
@RequestMapping("/api/voice/rooms")
@Tag(name = "受控语音媒体凭据")
@RequiredArgsConstructor
public class VoiceMediaCredentialController {

    /** SQL授权及真实签发用例；网关过滤器覆盖本接口并统一no-store。 */ private final VoiceMediaCredentialIssuer issuer;

    @GetMapping("/interaction-media-capabilities")
    @Operation(summary = "读取受控凭据候选能力", description = "需登录；只声明接口配置启用，不证明音轨就绪")
    public Capability capability() {
        return new Capability(issuer.enabled());
    }

    @PostMapping("/{id}/interaction/media-credentials")
    @Operation(
        summary = "签发当前受控媒体凭据",
        description = "当前Active身份、成员会话/租约/房间版本/席位/媒体轮次核验；未退场拒绝。无文本或管理权限。"
    )
    public VoiceMediaCredentialIssuer.Credential credential(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Valid @RequestBody Request request
    ) {
        return issuer.issue(user, id, request.sessionId(), request.expectedVersion());
    }

    public record Request(
        @Schema(description = "本人当前互动会话UUID，不是登录令牌")
        @NotNull
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String sessionId,
        @Schema(description = "当前已核验房间版本字符串")
        @NotNull
        @Pattern(regexp = "0|[1-9][0-9]{0,18}")
        String expectedVersion
    ) {}

    public record Capability(@Schema(description = "候选凭据接口启用；并非SFU或音轨就绪") boolean enabled) {}
}
