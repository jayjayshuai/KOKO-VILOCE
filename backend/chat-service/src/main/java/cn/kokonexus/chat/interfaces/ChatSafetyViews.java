package cn.kokonexus.chat.interfaces;

import cn.kokonexus.chat.domain.ChatBlock;
import cn.kokonexus.chat.domain.ChatReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 普通举报响应和审核证据分开投影，避免管理员权限缺失时泄露正文。 */
public final class ChatSafetyViews {

    private ChatSafetyViews() {}

    /** 本人的主动拉黑设置；ID 使用字符串防 JavaScript 精度损失。 */
    public record BlockView(
        @Schema(description = "拉黑记录 UUID，用于分页") String id,
        @Schema(description = "目标用户 ID 字符串") String targetId,
        @Schema(description = "目标公开用户名快照") String handle,
        @Schema(description = "目标显示名称快照") String displayName,
        @Schema(description = "创建时间，Asia/Shanghai") LocalDateTime createdAt
    ) {
        public static BlockView from(ChatBlock block) {
            return new BlockView(
                block.getId(),
                block.getTargetId().toString(),
                block.getTargetHandle(),
                block.getTargetName(),
                block.getCreatedAt()
            );
        }
    }

    /** 本人举报进度，不包含证据正文、其他举报人或审核员身份。 */
    public record ReportView(
        @Schema(description = "举报 UUID") String id,
        @Schema(description = "被举报消息 UUID") String messageId,
        @Schema(description = "消息所属会话 UUID") String conversationId,
        @Schema(description = "被举报用户 ID 字符串") String reportedUserId,
        @Schema(description = "HARASSMENT/SPAM/THREAT/OTHER") String reason,
        @Schema(description = "本人提交的举报说明") String detail,
        @Schema(description = "PENDING/RESOLVED/REJECTED") String status,
        @Schema(description = "审核版本，从 0 开始") long version,
        @Schema(description = "审核说明；未审核为空") String reviewNote,
        @Schema(description = "审核时间，Asia/Shanghai；未审核为空") LocalDateTime reviewedAt,
        @Schema(description = "举报时间，Asia/Shanghai") LocalDateTime createdAt
    ) {
        public static ReportView from(ChatReport report) {
            return new ReportView(
                report.getId(),
                report.getMessageId(),
                report.getConversationId(),
                report.getReportedUserId().toString(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getVersion(),
                report.getReviewNote(),
                report.getReviewedAt(),
                report.getCreatedAt()
            );
        }
    }

    /** 仅供有审核读取权限的用户；证据禁止日志输出。 */
    public record ModerationView(
        @Schema(description = "举报事实与进度") ReportView report,
        @Schema(description = "举报人用户 ID 字符串，仅审核员可见") String reporterId,
        @Schema(description = "服务端保存的消息正文证据，仅审核员可见") String evidenceBody
    ) {
        public static ModerationView from(ChatReport report) {
            return new ModerationView(
                ReportView.from(report),
                report.getReporterId().toString(),
                report.getEvidenceBody()
            );
        }
    }

    /** 只控制前端显示，所有审核调用仍由后端再次授权。 */
    public record Capabilities(
        @Schema(description = "当前共享会话是否具有聊天审核读取和处理权限") boolean moderation
    ) {}
}
