package cn.kokonexus.chat.interfaces;

import cn.kokonexus.chat.domain.ChatMessage;
import cn.kokonexus.chat.domain.Conversation;
import cn.kokonexus.chat.domain.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** HTTP 与 WebSocket 使用相同投影，避免实体暴露内部用户对键。 */
public final class ChatViews {

    private ChatViews() {}

    /** chat-service：MemberView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record MemberView(
        @Schema(description = "成员用户 ID") String userId,
        @Schema(description = "公开用户名快照") String handle,
        @Schema(description = "显示名称快照") String displayName,
        @Schema(description = "已读序号") long readSeq,
        @Schema(description = "入群历史边界，不可读取此序号及更早消息") long joinedSeq
    ) {
        public static MemberView from(Member member) {
            return new MemberView(
                member.getUserId().toString(),
                member.getHandle(),
                member.getDisplayName(),
                member.getReadSeq(),
                member.getJoinedSeq()
            );
        }
    }

    /** chat-service：ConversationView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record ConversationView(
        @Schema(description = "会话 UUID") String id,
        @Schema(description = "DIRECT 或 GROUP") String kind,
        @Schema(description = "群名；私信显示对方名称") String title,
        @Schema(description = "群主用户 ID") String ownerId,
        @Schema(description = "最后已提交消息序号") long lastSeq,
        @Schema(description = "当前成员，最多 50 人") List<MemberView> members
    ) {
        public static ConversationView from(Conversation c, List<Member> members, long userId) {
            String title = "DIRECT".equals(c.getKind())
                ? members
                      .stream()
                      .filter(m -> m.getUserId() != userId)
                      .map(Member::getDisplayName)
                      .findFirst()
                      .orElse(c.getTitle())
                : c.getTitle();
            return new ConversationView(
                c.getId(),
                c.getKind(),
                title,
                c.getOwnerId().toString(),
                c.getLastSeq(),
                members.stream().map(MemberView::from).toList()
            );
        }
    }

    /** chat-service：MessageView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record MessageView(
        @Schema(description = "服务端消息 UUID") String id,
        @Schema(description = "会话 UUID") String conversationId,
        @Schema(description = "会话内顺序；ACK 表示此序号已持久化") long seq,
        @Schema(description = "发送用户 ID") String senderId,
        @Schema(description = "发送时名称快照") String senderName,
        @Schema(description = "客户端幂等 UUID，重试必须复用") String clientMessageId,
        @Schema(description = "纯文本消息，最多 2000 字符") String body,
        @Schema(description = "服务端时间，Asia/Shanghai") LocalDateTime createdAt
    ) {
        public static MessageView from(ChatMessage m) {
            return new MessageView(
                m.getId(),
                m.getConversationId(),
                m.getSeq(),
                m.getSenderId().toString(),
                m.getSenderName(),
                m.getClientMessageId(),
                m.getBody(),
                m.getCreatedAt()
            );
        }
    }
}
