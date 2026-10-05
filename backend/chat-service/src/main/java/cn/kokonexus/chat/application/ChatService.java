package cn.kokonexus.chat.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.chat.domain.ChatMessage;
import cn.kokonexus.chat.domain.Conversation;
import cn.kokonexus.chat.domain.Member;
import cn.kokonexus.chat.persistence.ConversationMapper;
import cn.kokonexus.chat.persistence.MemberMapper;
import cn.kokonexus.chat.persistence.MessageMapper;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聊天用例；会话行锁先于成员锁，提交成功才允许传输层 ACK。 */
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 会话事实与序号锁。 */
    private final ConversationMapper conversations;
    /** 当前成员与已读游标。 */
    private final MemberMapper members;
    /** 幂等消息事实。 */
    private final MessageMapper messages;
    /** 同库反骚扰授权，用户对锁持有至本次消息或邀请提交。 */
    private final ChatSafetyService safety;

    /** 私信用户对唯一；竞争插入失败由调用层重新读取已提交会话。 */
    @Transactional
    public Conversation create(ChatIdentity owner, List<ChatIdentity> peers, String title, boolean group) {
        long ownerId = Long.parseLong(owner.id());
        if (peers.isEmpty() || peers.size() > 49 || (!group && peers.size() != 1)) {
            throw new IllegalArgumentException("私信需一名对方，群聊总人数必须为 2～50 人");
        }
        var unique = new java.util.HashSet<String>();
        unique.add(owner.id());
        for (var peer : peers) if (!unique.add(peer.id())) throw new IllegalArgumentException("成员不能重复或包含自己");
        // 多个联系锁按全局用户对顺序申请，避免两个建群请求相反顺序互锁。
        peers
            .stream()
            .sorted(
                java.util.Comparator.comparingLong((ChatIdentity peer) ->
                    Math.min(ownerId, Long.parseLong(peer.id()))
                ).thenComparingLong(peer -> Math.max(ownerId, Long.parseLong(peer.id())))
            )
            .forEach(peer -> safety.requireContactAllowed(ownerId, Long.parseLong(peer.id())));
        String key = group ? null : directKey(ownerId, Long.parseLong(peers.getFirst().id()));
        if (!group) {
            Conversation existing = findDirect(key);
            if (existing != null) return existing;
        }
        if (title == null || title.isBlank() || title.length() > 80) throw new IllegalArgumentException(
            "会话名称必须为 1～80 字符"
        );
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setKind(group ? "GROUP" : "DIRECT");
        conversation.setDirectKey(key);
        conversation.setOwnerId(ownerId);
        conversation.setTitle(title.trim());
        conversation.setLastSeq(0L);
        conversation.setStatus("ACTIVE");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(conversation.getCreatedAt());
        requireOne(conversations.insert(conversation));
        insertMember(conversation, owner);
        for (var peer : peers) insertMember(conversation, peer);
        return conversation;
    }

    public static String directKey(long first, long second) {
        return Math.min(first, second) + ":" + Math.max(first, second);
    }

    @Transactional(readOnly = true)
    public Conversation findDirect(String key) {
        return conversations.selectOne(Wrappers.<Conversation>lambdaQuery().eq(Conversation::getDirectKey, key));
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(long userId, String after, int size) {
        if (after != null) uuid(after);
        if (size < 1 || size > 100) throw new IllegalArgumentException("会话分页上限为 100");
        return conversations.listMine(userId, after, size);
    }

    /** 锁会话后检验成员并读取，避免与踢人/解散竞争绕过授权。 */
    @Transactional
    public List<Member> memberList(long userId, String conversationId) {
        Conversation conversation = activeLocked(conversationId);
        requireMember(conversation.getId(), userId);
        return members.selectList(
            Wrappers.<Member>lambdaQuery().eq(Member::getConversationId, conversationId).orderByAsc(Member::getUserId)
        );
    }

    @Transactional
    public List<ChatMessage> history(long userId, String conversationId, Long before, Long after, int size) {
        activeLocked(conversationId);
        Member member = requireMember(conversationId, userId);
        if (
            size < 1 ||
            size > 100 ||
            (before != null && before < 1) ||
            (after != null && after < 0) ||
            (before != null && after != null)
        ) throw new IllegalArgumentException("历史游标无效，分页上限为 100");
        var query = Wrappers.<ChatMessage>lambdaQuery()
            .eq(ChatMessage::getConversationId, conversationId)
            .gt(ChatMessage::getSeq, member.getJoinedSeq());
        if (before != null) query.lt(ChatMessage::getSeq, before);
        if (after != null) query.gt(ChatMessage::getSeq, after).orderByAsc(ChatMessage::getSeq);
        else query.orderByDesc(ChatMessage::getSeq);
        query.last("LIMIT " + size); // size 已限定为整数，不能传入客户端 SQL。
        var result = new ArrayList<>(messages.selectList(query));
        if (after == null) java.util.Collections.reverse(result);
        return result;
    }

    /** 同一幂等键不得改变正文；序号与消息在同一本地事务中提交。 */
    @Transactional
    public ChatMessage send(long userId, String conversationId, String clientMessageId, String body) {
        uuid(clientMessageId);
        if (body == null || body.isBlank() || body.length() > 2000 || body.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("消息必须为 1～2000 字符的非空纯文本");
        }
        Conversation conversation = activeLocked(conversationId);
        Member member = requireMember(conversationId, userId);
        ChatMessage existing = messages.selectOne(
            Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getSenderId, userId)
                .eq(ChatMessage::getClientMessageId, clientMessageId)
        );
        if (existing != null) {
            if (!existing.getBody().equals(body)) throw new IllegalStateException("相同消息 UUID 不能更改正文");
            return existing;
        }
        if ("DIRECT".equals(conversation.getKind())) {
            var directMembers = members.selectList(
                Wrappers.<Member>lambdaQuery().eq(Member::getConversationId, conversationId)
            );
            if (directMembers.size() != 2) throw new IllegalStateException("私信成员状态异常");
            long peerId = directMembers
                .stream()
                .filter(value -> value.getUserId() != userId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("私信目标不存在"))
                .getUserId();
            safety.requireContactAllowed(userId, peerId);
        }
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(conversationId);
        message.setSeq(Math.addExact(conversation.getLastSeq(), 1));
        message.setSenderId(userId);
        message.setSenderName(member.getDisplayName());
        message.setClientMessageId(clientMessageId);
        message.setBody(body);
        message.setCreatedAt(LocalDateTime.now());
        requireOne(messages.insert(message));
        conversation.setLastSeq(message.getSeq());
        conversation.setUpdatedAt(message.getCreatedAt());
        requireOne(conversations.updateById(conversation));
        members.markRead(conversationId, userId, message.getSeq());
        return message;
    }

    @Transactional
    public void read(long userId, String id, long seq) {
        Conversation conversation = activeLocked(id);
        requireMember(id, userId);
        if (seq < 0 || seq > conversation.getLastSeq()) throw new IllegalArgumentException("已读序号超出范围");
        members.markRead(id, userId, seq);
    }

    @Transactional
    public void add(long userId, String id, ChatIdentity target) {
        Conversation conversation = ownerLocked(userId, id);
        safety.requireContactAllowed(userId, Long.parseLong(target.id()));
        if (members.selectCount(Wrappers.<Member>lambdaQuery().eq(Member::getConversationId, id)) >= 50) {
            throw new IllegalStateException("群聊人数已达 50 人上限");
        }
        if (
            members.selectCount(
                Wrappers.<Member>lambdaQuery()
                    .eq(Member::getConversationId, id)
                    .eq(Member::getUserId, Long.parseLong(target.id()))
            ) > 0
        ) {
            throw new IllegalStateException("用户已在群中");
        }
        insertMember(conversation, target);
    }

    @Transactional
    public void remove(long userId, String id, long targetId) {
        Conversation conversation = activeLocked(id);
        requireMember(id, userId);
        if (!"GROUP".equals(conversation.getKind())) throw new IllegalArgumentException("不能退出或修改私信成员");
        if (targetId == conversation.getOwnerId()) throw new IllegalStateException("群主不能退出，请先解散群聊");
        if (userId != targetId && userId != conversation.getOwnerId()) throw new ForbiddenOperationException(
            "只有群主能移除其他成员"
        );
        int count = members.delete(
            Wrappers.<Member>lambdaQuery().eq(Member::getConversationId, id).eq(Member::getUserId, targetId)
        );
        if (count != 1) throw new ResourceNotFoundException("成员不存在");
    }

    @Transactional
    public void rename(long userId, String id, String title) {
        Conversation conversation = ownerLocked(userId, id);
        if (title == null || title.isBlank() || title.length() > 80) throw new IllegalArgumentException(
            "群名称必须为 1～80 字符"
        );
        conversation.setTitle(title.trim());
        conversation.setUpdatedAt(LocalDateTime.now());
        requireOne(conversations.updateById(conversation));
    }

    @Transactional
    public void close(long userId, String id) {
        Conversation conversation = ownerLocked(userId, id);
        conversation.setStatus("CLOSED");
        conversation.setUpdatedAt(LocalDateTime.now());
        requireOne(conversations.updateById(conversation));
    }

    private Conversation ownerLocked(long userId, String id) {
        Conversation conversation = activeLocked(id);
        requireMember(id, userId);
        if (
            !"GROUP".equals(conversation.getKind()) || conversation.getOwnerId() != userId
        ) throw new ForbiddenOperationException("仅群主可管理群聊");
        return conversation;
    }

    private Conversation activeLocked(String id) {
        uuid(id);
        Conversation conversation = conversations.lock(id);
        if (conversation == null || !"ACTIVE".equals(conversation.getStatus())) throw new ResourceNotFoundException(
            "会话不存在或已关闭"
        );
        return conversation;
    }

    private Member requireMember(String id, long userId) {
        Member member = members.selectOne(
            Wrappers.<Member>lambdaQuery().eq(Member::getConversationId, id).eq(Member::getUserId, userId)
        );
        if (member == null) throw new ResourceNotFoundException("会话不存在或无访问权限");
        return member;
    }

    private void insertMember(Conversation conversation, ChatIdentity identity) {
        Member member = new Member();
        member.setId(UUID.randomUUID().toString());
        member.setConversationId(conversation.getId());
        member.setUserId(Long.parseLong(identity.id()));
        member.setHandle(identity.handle());
        member.setDisplayName(identity.displayName());
        member.setJoinedSeq(conversation.getLastSeq());
        member.setReadSeq(conversation.getLastSeq());
        requireOne(members.insert(member));
    }

    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("聊天状态写入失败");
    }

    public static void uuid(String value) {
        if (
            value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        ) throw new IllegalArgumentException("UUID 格式无效");
    }
}
