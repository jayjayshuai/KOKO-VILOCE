package cn.kokonexus.voice.interfaces;

import cn.kokonexus.voice.application.VoiceApplicationService;
import cn.kokonexus.voice.application.VoiceInteractionDirectory;
import cn.kokonexus.voice.application.VoiceInteractionService;
import cn.kokonexus.voice.domain.VoiceInteraction.CommandType;
import cn.kokonexus.voice.interfaces.VoiceInteractionViews.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 互动核心独立接口；现有可信网关过滤器覆盖全部路径，禁止客户端身份字段。 */
@RestController
@RequestMapping("/api/voice/rooms")
@Tag(name = "语音房互动核心")
@RequiredArgsConstructor
public class VoiceInteractionController {

    @GetMapping("/{id}/interaction/actions")
    @Operation(
        summary = "房主管理方读取操作审计",
        description = "每次当前权限，版本降序游标；不返回成员会话/媒体凭据。"
    )
    public ActionPage actions(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Parameter(description = "原页nextBefore，首批不传") @RequestParam(required = false) String before,
        @Parameter(description = "1～50，默认20") @RequestParam(defaultValue = "20") int size
    ) {
        return service.actions(user, id, before, size);
    }

    /** 持久状态/权限/幂等事务代理。 */ private final VoiceInteractionService service;
    /** 持锁前的身份目录。 */ private final VoiceInteractionDirectory directory;
    /** 原媒体编排，不替代该调用为假成功。 */ private final VoiceApplicationService rooms;

    @GetMapping("/interaction-capabilities")
    @Operation(summary = "读取互动核心候选开关", description = "需登录；不暴露成员/内部媒体配置，不声明媒体就绪。")
    public Capabilities features() {
        return service.features();
    }

    @PostMapping("/controlled")
    @Operation(
        summary = "创建受控房间候选",
        description = "核心开关默认关闭；媒体授权未开放，不签发旧JWT。已有LEGACY房间不转换。"
    )
    public VoiceController.VoiceRoomView create(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @Valid @RequestBody VoiceController.CreateVoiceRoomRequest request
    ) {
        service.requireEnabled();
        return VoiceController.VoiceRoomView.from(
            rooms.createControlled(user, request.slug(), request.title(), request.topic(), request.maxParticipants())
        );
    }

    @GetMapping("/{id}/interaction/capabilities")
    @Operation(summary = "读取互动核心开关与版本", description = "不返回成员/申请；媒体准备度当前固定false。")
    public Capabilities capabilities(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id
    ) {
        return service.capabilities(user, id);
    }

    @GetMapping("/{id}/interaction")
    @Operation(summary = "读取有界互动快照", description = "仅有效成员或房主；成员会话只返回本人，过期惰性回收。")
    public Snapshot snapshot(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id
    ) {
        return service.snapshot(user, id);
    }

    @GetMapping("/{id}/interaction/sync")
    @Operation(
        summary = "按已知版本同步房间事实",
        description = "每次当前授权与租约回收；同版本不发送私有名单，版本变化返回完整授权快照。不是RTC事件。"
    )
    public SyncView sync(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Parameter(description = "客户端最后已读取的版本字符串；首读不传") @RequestParam(
            required = false
        ) String knownVersion
    ) {
        return service.sync(user, id, knownVersion);
    }

    @GetMapping("/{id}/interaction/receipts/{requestId}")
    @Operation(
        summary = "核对本人原请求的持久提交收据",
        description = "使用登录身份及原UUID，仅本人可读；未找到不证明在途命令失败。不会重试命令或恢复过期会话。"
    )
    public ReceiptView receipt(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Parameter(description = "原JOIN或COMMAND请求UUID，不是成员sessionId") @PathVariable String requestId
    ) {
        return service.receipt(user, id, requestId);
    }

    @PostMapping("/{id}/interaction/join")
    @Operation(
        summary = "加入互动成员会话",
        description = "身份目录名称/SQL人数锁，90秒租约；不返回LiveKit JWT，不代表媒体在线。"
    )
    public Ack join(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Valid @RequestBody Join request
    ) {
        service.requireEnabled();
        return service.join(user, id, request.requestId(), request.expectedVersion(), directory.name(user));
    }

    @PostMapping("/{id}/interaction/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "续约本人互动会话", description = "过期或旧session不能复活；建议25秒一次。")
    public void heartbeat(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Valid @RequestBody Heartbeat request
    ) {
        service.heartbeat(user, id, request.sessionId());
    }

    @PostMapping("/{id}/interaction/commands")
    @Operation(
        summary = "执行幂等麦位/成员管理命令",
        description = "原UUID与参数绑定，当前角色和全房间版本检查；状态/审计/收据同事务，媒体未开放。"
    )
    public Ack command(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long user,
        @PathVariable long id,
        @Valid @RequestBody Command request
    ) {
        Long target = null;
        if (request.targetUserId() != null) try {
            target = Long.valueOf(request.targetUserId());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("目标用户标识超界");
        }
        return service.command(
            user,
            id,
            request.requestId(),
            request.sessionId(),
            request.expectedVersion(),
            request.type(),
            request.seatNo(),
            target,
            request.seatRequestId(),
            request.value()
        );
    }

    public record Join(
        @Schema(description = "客户端新UUID；未知回复重试不得改参")
        @NotNull
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String requestId,
        @Schema(description = "capabilities的房间版本字符串")
        @NotNull
        @Pattern(regexp = "0|[1-9][0-9]{0,18}")
        String expectedVersion
    ) {}

    public record Heartbeat(
        @Schema(description = "本人服务器会话UUID，不是登录凭据")
        @NotNull
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String sessionId
    ) {}

    public record Command(
        @Schema(description = "客户端新UUID，绑定全部参数")
        @NotNull
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String requestId,
        @Schema(description = "本人当前会话UUID")
        @NotNull
        @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        String sessionId,
        @Schema(description = "快照版本字符串，禁止转JS number")
        @NotNull
        @Pattern(regexp = "0|[1-9][0-9]{0,18}")
        String expectedVersion,
        @Schema(description = "完整命令类型，字段按该类型精确匹配") @NotNull CommandType type,
        @Schema(description = "席位类命令需1～8，其余不得传", nullable = true) Integer seatNo,
        @Schema(description = "邀请/抱麦/房管/转让需目标正数ID字符串，其余不得传", nullable = true)
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        String targetUserId,
        @Schema(description = "接受/拒绝/取消需原预约UUID，其余不得传", nullable = true) String seatRequestId,
        @Schema(description = "MUTE/LOCK/ADMIN需明确布尔值，其余不得传", nullable = true) Boolean value
    ) {}
}
