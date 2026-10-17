package cn.kokonexus.chat.interfaces;

import cn.kokonexus.chat.application.NativeCallMediaService;
import cn.kokonexus.chat.application.NativeCallService;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

/** 私信/群聊直接通话；网关注入身份和网站摘要，正文没有userId或mediaIdentity授权字段。 */
@RestController
@RequestMapping("/api/chat/calls")
@Tag(name = "站内私信与群聊通话")
@lombok.RequiredArgsConstructor
public class NativeCallController {

    /** 默认禁用时只有能力查询，不访问业务库或媒体。 */ private final ObjectProvider<NativeCallService> calls;
    /** 实际音频凭据不等于声音已连通。 */ private final ObjectProvider<NativeCallMediaService> media;

    @GetMapping("/capabilities")
    @Operation(summary = "查询原生通话是否启用", description = "须登录；开启只表示配置，不代表当前网络或麦克风可用")
    public Capability capability() {
        return new Capability(calls.getIfAvailable() != null);
    }

    @GetMapping("/current")
    @Operation(summary = "查询本人当前来电或通话", description = "最多一个；无通话返回204；只向明确邀请的当前成员返回")
    public org.springframework.http.ResponseEntity<NativeCallService.View> current(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user
    ) {
        var current = service().current(user);
        return current == null
            ? org.springframework.http.ResponseEntity.noContent().build()
            : org.springframework.http.ResponseEntity.ok(current);
    }

    @PostMapping
    @Operation(
        summary = "邀请私信对方或指定群成员语音通话",
        description = "原UUID幂等；私信自动选对方，群聊最多7个邀请对象。不会自动采集被呼叫者麦克风"
    )
    public NativeCallService.View invite(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @Parameter(hidden = true) @RequestHeader("X-Koko-Website-Scope") String scope,
        @Valid @RequestBody Invite request
    ) {
        return service().invite(
            user,
            scope,
            request.conversationId(),
            request.requestId(),
            request.targets() == null ? null : request.targets().stream().map(Long::parseLong).toList()
        );
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取原通话当前事实", description = "成员移除重入不能继承旧邀请；不会自动接听或签发音频凭据")
    public NativeCallService.View read(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable String id
    ) {
        return service().read(user, id);
    }

    @PostMapping("/{id}/commands")
    @Operation(
        summary = "接听、拒接、离开或结束通话",
        description = "须原UUID、原版本；结果未知时查询/重试原命令，不自动创建新命令"
    )
    public NativeCallService.Result command(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @Parameter(hidden = true) @RequestHeader("X-Koko-Website-Scope") String scope,
        @PathVariable String id,
        @Valid @RequestBody Command request
    ) {
        return service().command(user, scope, id, request.requestId(), request.expectedVersion(), request.type());
    }

    @PostMapping("/{id}/heartbeat")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(summary = "续租本人原接听网站", description = "不能复活35秒过期租约，也不延长45秒响铃期限")
    public void heartbeat(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @Parameter(hidden = true) @RequestHeader("X-Koko-Website-Scope") String scope,
        @PathVariable String id
    ) {
        service().heartbeat(user, scope, id);
    }

    @PostMapping("/{id}/media-credentials")
    @Operation(
        summary = "签发已接听通话的麦克风凭据",
        description = "当前账号、成员、同意及网站核验；网络准备后再次检查；不授予视频、数据或管理能力"
    )
    public NativeCallMediaService.Credential credential(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @Parameter(hidden = true) @RequestHeader("X-Koko-Website-Scope") String scope,
        @PathVariable String id
    ) {
        var available = media.getIfAvailable();
        if (available == null) throw unavailable();
        return available.issue(user, scope, id);
    }

    private NativeCallService service() {
        var available = calls.getIfAvailable();
        if (available == null) throw unavailable();
        return available;
    }

    private ExternalDependencyUnavailableException unavailable() {
        return new ExternalDependencyUnavailableException("站内直接通话尚未开放", null);
    }

    public record Capability(@Schema(description = "配置是否启用，不证明声音连通") boolean enabled) {}

    public record Invite(
        @Schema(description = "所属聊天UUID")
        @NotNull
        @Pattern(regexp = "[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        String conversationId,
        @Schema(description = "原邀请UUID，超时重试复用")
        @NotNull
        @Pattern(regexp = "[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        String requestId,
        @Schema(description = "群聊显式邀请的1～7个用户ID字符串；私信可空")
        @Size(max = 7)
        List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> targets
    ) {}

    public record Command(
        @Schema(description = "原命令UUID，不能换内容重用")
        @NotNull
        @Pattern(regexp = "[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        String requestId,
        @Schema(description = "首次操作核对的控制版本；重试不改，结束全体要求与当前版本一致")
        @Min(1)
        long expectedVersion,
        @Schema(description = "ACCEPT/DECLINE/LEAVE/END")
        @NotNull
        @Pattern(regexp = "ACCEPT|DECLINE|LEAVE|END")
        String type
    ) {}
}
