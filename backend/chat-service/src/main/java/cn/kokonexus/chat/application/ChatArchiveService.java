package cn.kokonexus.chat.application;

import cn.kokonexus.chat.domain.ChatBookmark;
import cn.kokonexus.chat.domain.ChatMessage;
import cn.kokonexus.chat.domain.Member;
import cn.kokonexus.chat.persistence.ArchiveMapper;
import cn.kokonexus.chat.persistence.ConversationMapper;
import cn.kokonexus.chat.persistence.MemberMapper;
import cn.kokonexus.chat.persistence.MessageMapper;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 个人历史工具，收藏不复制正文或延长历史权限；会话锁先于成员和收藏读取。 */
@Service
@RequiredArgsConstructor
public class ChatArchiveService {

    /** 每次最多扫描的序号跨度，不因没有匹配而无界扫描全部历史。 */
    private static final int SEARCH_WINDOW = 2000;
    /** 每人每会话最多保存的收藏引用。 */
    private static final int BOOKMARK_LIMIT = 1000;
    /** 与消息、成员变更共用的会话锁。 */
    private final ConversationMapper conversations;
    /** 当前成员和加入历史边界。 */
    private final MemberMapper members;
    /** 不可伪造的消息事实。 */
    private final MessageMapper messages;
    /** 本人收藏和窗口检索。 */
    private final ArchiveMapper archive;

    /** 有界字面检索；空结果可能仍有 nextBefore，调用方不能把它当检索结束。 */
    @Transactional(timeout = 3)
    public MessageSlice search(long userId, String id, String query, Long before, int size) {
        String pattern = literalPattern(query);
        long upper = upperBound(userId, id, before, size);
        Member member = member(id, userId);
        long floor = Math.max(member.getJoinedSeq(), Math.max(0, upper - 1 - SEARCH_WINDOW));
        var found = archive.search(id, floor, upper, pattern, size + 1);
        Long next =
            found.size() > size ? found.get(size - 1).getSeq() : floor > member.getJoinedSeq() ? floor + 1 : null;
        return new MessageSlice(List.copyOf(found.subList(0, Math.min(size, found.size()))), next);
    }

    /** 收藏查询每次重新验证当前成员/边界，不缓存曾经拥有的阅读权限。 */
    @Transactional(timeout = 3)
    public MessageSlice bookmarked(long userId, String id, Long before, int size) {
        long upper = upperBound(userId, id, before, size);
        Member member = member(id, userId);
        var found = archive.bookmarked(userId, id, member.getJoinedSeq(), upper, size + 1);
        Long next = found.size() > size ? found.get(size - 1).getSeq() : null;
        return new MessageSlice(List.copyOf(found.subList(0, Math.min(size, found.size()))), next);
    }

    /** 只保存可读真实消息的引用；会话锁序列化重复收藏和配额。 */
    @Transactional(timeout = 3)
    public void save(long userId, String id, String messageId) {
        upperBound(userId, id, null, 1);
        ChatService.uuid(messageId);
        Member member = member(id, userId);
        ChatMessage message = messages.selectById(messageId);
        if (message == null || !id.equals(message.getConversationId()) || message.getSeq() <= member.getJoinedSeq()) {
            throw new ResourceNotFoundException("消息不存在或无收藏权限");
        }
        if (archive.selectCount(ownedMessage(userId, id, messageId)) != 0) {
            return;
        }
        if (archive.selectCount(ownedConversation(userId, id)) >= BOOKMARK_LIMIT) {
            throw new IllegalStateException("每人每会话最多收藏 1000 条消息，可先取消或清空本人收藏");
        }
        ChatBookmark bookmark = new ChatBookmark();
        bookmark.setId(UUID.randomUUID().toString());
        bookmark.setOwnerId(userId);
        bookmark.setConversationId(id);
        bookmark.setMessageId(messageId);
        bookmark.setMessageSeq(message.getSeq());
        bookmark.setCreatedAt(LocalDateTime.now());
        if (archive.insert(bookmark) != 1) {
            throw new IllegalStateException("消息收藏保存失败");
        }
    }

    /** 取消本人引用，未收藏也返回成功；不透露他人收藏状态或已失去权限的正文。 */
    @Transactional(timeout = 3)
    public void remove(long userId, String id, String messageId) {
        upperBound(userId, id, null, 1);
        ChatService.uuid(messageId);
        int changed = archive.delete(ownedMessage(userId, id, messageId));
        if (changed < 0 || changed > 1) {
            throw new IllegalStateException("消息收藏删除异常");
        }
    }

    /** 清空本会话的本人引用，包含重加入后不可读的旧引用；不修改真实消息。 */
    @Transactional(timeout = 3)
    public void clear(long userId, String id) {
        upperBound(userId, id, null, 1);
        if (archive.delete(ownedConversation(userId, id)) < 0) {
            throw new IllegalStateException("消息收藏清空异常");
        }
    }

    private long upperBound(long userId, String id, Long before, int size) {
        ChatService.uuid(id);
        if (size < 1 || size > 50 || (before != null && before < 1)) {
            throw new IllegalArgumentException("历史工具游标无效，分页上限为 50");
        }
        var conversation = conversations.lock(id);
        if (conversation == null || !"ACTIVE".equals(conversation.getStatus())) {
            throw new ResourceNotFoundException("会话不存在或已关闭");
        }
        member(id, userId);
        long upper = Math.addExact(conversation.getLastSeq(), 1);
        if (before != null && before > upper) {
            throw new IllegalArgumentException("历史工具游标超过最后已提交消息");
        }
        return before == null ? upper : before;
    }

    private Member member(String id, long userId) {
        var member = members.selectOne(
            Wrappers.<Member>lambdaQuery().eq(Member::getConversationId, id).eq(Member::getUserId, userId)
        );
        if (member == null) {
            throw new ResourceNotFoundException("会话不存在或无访问权限");
        }
        return member;
    }

    static String literalPattern(String value) {
        if (value == null || value.strip().length() < 2 || value.length() > 64 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("搜索词必须为 2～64 字符纯文本");
        }
        return value.strip().replace("=", "==").replace("%", "=%").replace("_", "=_");
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatBookmark> ownedConversation(
        long userId,
        String id
    ) {
        return Wrappers.<ChatBookmark>lambdaQuery()
            .eq(ChatBookmark::getOwnerId, userId)
            .eq(ChatBookmark::getConversationId, id);
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatBookmark> ownedMessage(
        long userId,
        String id,
        String messageId
    ) {
        return ownedConversation(userId, id).eq(ChatBookmark::getMessageId, messageId);
    }

    /** 内部查询切片；HTTP 层必须转换为不含持久化内部字段的消息投影。 */
    public record MessageSlice(
        @Schema(description = "本批可读真实消息，按会话内序号倒序") List<ChatMessage> items,
        @Schema(description = "下一批独占序号上界；无后续为 null，空 items 也可能有游标") Long nextBefore
    ) {}
}
