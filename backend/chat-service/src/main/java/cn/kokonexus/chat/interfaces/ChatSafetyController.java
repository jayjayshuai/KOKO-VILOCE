package cn.kokonexus.chat.interfaces;

import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.chat.application.ChatDirectory;
import cn.kokonexus.chat.application.ChatSafetyService;
import cn.kokonexus.chat.interfaces.ChatSafetyViews.*;
import cn.kokonexus.chat.transport.ChatPermissionProvider;
import cn.kokonexus.common.api.ForbiddenOperationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 反骚扰与举报边界；普通与审核响应严格分离，审核重新校验共享 Sa-Token。 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "聊天反骚扰与举报审核")
@RequiredArgsConstructor
public class ChatSafetyController {

    /** 反骚扰事务用例。 */
    private final ChatSafetyService safety;
    /** 通过 Dubbo 精确查询目标，不接受客户端自造账号投影。 */
    private final ChatDirectory directory;

    @GetMapping("/safety/capabilities")
    @Operation(
        summary = "读取本人聊天审核能力",
        description = "默认无审核员；能力仅用于显示，审核 API 仍独立检查权限。"
    )
    public Capabilities capabilities(@Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId) {
        authenticated(userId);
        return new Capabilities(
            StpUtil.hasPermission(ChatPermissionProvider.READ) && StpUtil.hasPermission(ChatPermissionProvider.REVIEW)
        );
    }

    @GetMapping("/blocks")
    @Operation(summary = "分页读取本人的拉黑设置", description = "不返回对方是否拉黑自己；最大 100 条。")
    public List<BlockView> blocks(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "50") int size
    ) {
        return safety.blocked(userId, after, size).stream().map(BlockView::from).toList();
    }

    @PostMapping("/blocks")
    @Operation(summary = "拉黑用户", description = "重复操作幂等；限制新私信及彼此新群邀请，不屏蔽已有共同群。")
    public BlockView block(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody ChatController.HandleRequest request
    ) {
        return BlockView.from(safety.block(userId, directory.byHandle(request.handle())));
    }

    @DeleteMapping("/blocks/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "解除本人对目标的拉黑", description = "重复解除成功，不改变目标对自己的设置。")
    public void unblock(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long targetId
    ) {
        safety.unblock(userId, targetId);
    }

    @PostMapping("/reports")
    @Operation(
        summary = "举报当前有权读取的他人消息",
        description = "证据来自数据库；每人每条消息一份，原内容重试幂等，24 小时最多 20 份。"
    )
    public ReportView report(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody ReportRequest request
    ) {
        return ReportView.from(
            safety.report(userId, request.conversationId(), request.messageId(), request.reason(), request.detail())
        );
    }

    @GetMapping("/reports")
    @Operation(summary = "分页读取本人举报进度", description = "不包含证据正文及审核员身份；最大 100 条。")
    public List<ReportView> reports(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "50") int size
    ) {
        return safety.mine(userId, after, size).stream().map(ReportView::from).toList();
    }

    @GetMapping("/reports/{id}")
    @Operation(summary = "读取本人单份举报进度", description = "其他人的举报返回 404；离群后仍可查询自己的结论。")
    public ReportView report(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id
    ) {
        return ReportView.from(safety.mine(userId, id));
    }

    @GetMapping("/moderation/reports")
    @Operation(
        summary = "审核员分页读取举报及证据",
        description = "必须具有 chat:reports:read 权限；不对普通用户开放。"
    )
    public List<ModerationView> queue(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(defaultValue = "PENDING") String status,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "50") int size
    ) {
        authorized(userId, ChatPermissionProvider.READ);
        return safety.queue(status, after, size).stream().map(ModerationView::from).toList();
    }

    @PatchMapping("/moderation/reports/{id}")
    @Operation(
        summary = "审核员提交最终决定",
        description = "必须具有处理权限并携带版本及说明；决定与审计原子提交，不自动封禁账号。"
    )
    public ReportView review(
        @Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable String id,
        @Valid @RequestBody ReviewRequest request
    ) {
        authorized(userId, ChatPermissionProvider.REVIEW);
        return ReportView.from(safety.review(userId, id, request.version(), request.decision(), request.note()));
    }

    private static void authenticated(long userId) {
        StpUtil.checkLogin();
        if (StpUtil.getLoginIdAsLong() != userId) {
            throw new ForbiddenOperationException("会话身份不一致");
        }
    }

    private static void authorized(long userId, String permission) {
        authenticated(userId);
        StpUtil.checkPermission(permission);
    }

    /** 举报输入不接受证据正文、发送者或举报人 ID。 */
    public record ReportRequest(
        @Schema(description = "当前可读取的会话 UUID") @NotBlank String conversationId,
        @Schema(description = "当前可读取的他人消息 UUID") @NotBlank String messageId,
        @Schema(description = "举报原因，HARASSMENT/SPAM/THREAT/OTHER")
        @NotBlank
        @Pattern(regexp = "HARASSMENT|SPAM|THREAT|OTHER")
        String reason,
        @Schema(description = "举报说明，1～500 字符纯文本") @NotBlank @Size(max = 500) String detail
    ) {}

    /** 审核输入必须携带版本，不能无条件覆盖他人的最终决定。 */
    public record ReviewRequest(
        @Schema(description = "当前举报版本，非负且必须提供") @NotNull @Min(0) Long version,
        @Schema(description = "最终决定，RESOLVED 或 REJECTED")
        @NotBlank
        @Pattern(regexp = "RESOLVED|REJECTED")
        String decision,
        @Schema(description = "人工审核说明，1～500 字符") @NotBlank @Size(max = 500) String note
    ) {}
}
