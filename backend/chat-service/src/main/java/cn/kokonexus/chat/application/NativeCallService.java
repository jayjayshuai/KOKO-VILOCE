package cn.kokonexus.chat.application;

import cn.kokonexus.chat.domain.Member;
import cn.kokonexus.chat.domain.NativeCall;
import cn.kokonexus.chat.domain.NativeCallCommand;
import cn.kokonexus.chat.domain.NativeCallParticipant;
import cn.kokonexus.chat.persistence.ConversationMapper;
import cn.kokonexus.chat.persistence.MemberMapper;
import cn.kokonexus.chat.persistence.NativeCallMapper;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原生聊天通话控制面；不创建公开语音房，不签发媒体Token，接听不等于声音连通。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallService {

    /** 允许的显式控制动作，不接受任意SQL状态字符串。 */
    private static final Set<String> COMMANDS = Set.of("ACCEPT", "DECLINE", "LEAVE", "END");
    /** 聊天会话为第一把锁，沿用发消息/成员管理顺序。 */
    private final ConversationMapper conversations;
    /** 当前成员UUID，不以重新入群后可能相同的joined_seq替代。 */
    private final MemberMapper members;
    /** 通话/参与者/原命令/媒体清退意图的同库事实。 */
    private final NativeCallMapper calls;
    /** 当前双向联系授权，邀请和接听不绕过拉黑。 */
    private final ChatSafetyService safety;
    /** 原Netty用户级同步版本，与控制事实同事务递增。 */
    private final ChatSyncWriter sync;

    /** 私信自动选择对方；群聊由发起者明确选择1～7位当前成员。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public View invite(long actor, String websiteScope, String conversationId, String requestId, List<Long> targets) {
        positive(actor);
        scope(websiteScope);
        uuid(conversationId);
        uuid(requestId);
        if (targets != null && targets.size() > 7) throw new IllegalArgumentException("通话邀请人数超限");
        var conversation = conversations.lock(conversationId);
        active(conversation);
        var current = members.currentMembers(conversationId);
        member(current, actor);
        List<Long> requested;
        if ("DIRECT".equals(conversation.getKind())) {
            if (current.size() != 2) throw conflict("私信成员状态异常");
            requested = current
                .stream()
                .map(Member::getUserId)
                .filter(id -> id != actor)
                .toList();
            if (
                targets != null && !targets.isEmpty() && !new java.util.HashSet<>(targets).equals(Set.copyOf(requested))
            ) throw new IllegalArgumentException("私信只能呼叫对方");
        } else {
            if (
                targets == null ||
                targets.isEmpty() ||
                targets.size() > 7 ||
                targets.stream().anyMatch(id -> id == null || id <= 0 || id == actor) ||
                targets.stream().distinct().count() != targets.size()
            ) throw new IllegalArgumentException("群语音请选择1～7位不同的其他成员");
            requested = targets.stream().sorted().toList();
        }
        var selected = new ArrayList<Member>();
        selected.add(member(current, actor));
        for (long target : requested) selected.add(member(current, target));
        selected.sort(Comparator.comparingLong(Member::getUserId));
        requested
            .stream()
            .sorted(
                Comparator.comparingLong((Long id) -> Math.min(id, actor)).thenComparingLong(id -> Math.max(id, actor))
            )
            .forEach(id -> safety.requireContactAllowed(actor, id));
        String fingerprint = hash(
            "INVITE|" +
                websiteScope +
                "|" +
                requested.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
        );
        var old = calls.original(conversationId, actor, requestId);
        if (old != null) {
            if (!fingerprint.equals(old.getRequestHash())) throw conflict("原通话请求不能更换参与者或网站会话");
            authorized(old, actor);
            return view(old, calls.participants(old.getId()));
        }
        if (calls.activeConversation(conversationId) != null) throw conflict("这个聊天已有通话，请先结束原通话");
        NativeCall row = new NativeCall();
        row.setId(UUID.randomUUID().toString());
        row.setConversationId(conversationId);
        row.setInitiatorId(actor);
        row.setClientRequestId(requestId);
        row.setRequestHash(fingerprint);
        try {
            one(calls.insertCall(row));
            for (var item : selected) {
                var participant = new NativeCallParticipant();
                participant.setCallId(row.getId());
                participant.setUserId(item.getUserId());
                participant.setMemberId(item.getId());
                participant.setDisplayName(item.getDisplayName());
                participant.setState(item.getUserId() == actor ? "ACCEPTED" : "INVITED");
                if (item.getUserId() == actor) {
                    participant.setWebsiteScope(websiteScope);
                    participant.setMediaIdentity(UUID.randomUUID().toString());
                }
                one(calls.insertParticipant(participant));
            }
        } catch (DuplicateKeyException busy) {
            throw conflict("参与者已有来电或通话，请稍后重试");
        }
        sync.changed(selected.stream().map(Member::getUserId).toList());
        return view(calls.lock(row.getId()), calls.participants(row.getId()));
    }

    /** 查询事实不重发邀请，也不把ACTIVE标为已连接音频。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public View read(long actor, String callId) {
        NativeCall row = load(actor, callId);
        return view(row, calls.participants(callId));
    }

    /** 全局来电/当前通话事实；不暴露未明确邀请的其他群成员。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public View current(long actor) {
        positive(actor);
        String id = calls.currentForUser(actor);
        if (id == null) return null;
        NativeCall row = load(actor, id);
        return row.getState().equals("ENDED") ? null : view(row, calls.participants(id));
    }

    /** 短事务媒体授权，不执行网络；只有明确接听且仍有至少两位有效接听者可获取音频凭据。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public MediaGrant mediaGrant(long actor, String websiteScope, String callId) {
        scope(websiteScope);
        NativeCall row = load(actor, callId);
        var people = calls.participants(callId);
        var self = person(people, actor);
        lockContacts(row, people);
        if (
            !"ACTIVE".equals(row.getState()) ||
            !"ACCEPTED".equals(self.getState()) ||
            !websiteScope.equals(self.getWebsiteScope()) ||
            !self.isLeaseFresh() ||
            count(people, "ACCEPTED") < 2 ||
            people.stream().anyMatch(p -> "ACCEPTED".equals(p.getState()) && !p.isLeaseFresh()) ||
            !calls.eligible(callId)
        ) throw new ForbiddenOperationException("当前通话未接听、已过期或网站会话不匹配");
        return new MediaGrant(callId, self.getMediaIdentity(), self.getDisplayName());
    }

    /** 媒体已验签后再比对当前SQL事实；旧身份和另一个网站不能继承接听许可。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public boolean mediaAllows(long actor, String websiteScope, String callId, String identity) {
        try {
            return mediaGrant(actor, websiteScope, callId).identity().equals(identity);
        } catch (ResourceNotFoundException | ForbiddenOperationException denied) {
            return false;
        }
    }

    /** 注销只清退匹配的原接听网站；新网站、其他成员及新通话不受旧注销请求影响。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public boolean retireWebsite(long actor, String websiteScope) {
        positive(actor);
        scope(websiteScope);
        String id = calls.currentForUser(actor);
        if (id == null) return true;
        var initial = calls.selectById(id);
        if (initial == null) return true;
        conversations.lock(initial.getConversationId());
        var row = calls.lock(id);
        var people = calls.participants(id);
        var self = person(people, actor);
        if (!"ACCEPTED".equals(self.getState()) || !websiteScope.equals(self.getWebsiteScope())) return true;
        if ("RINGING".equals(row.getState())) end(row, "WEBSITE_ENDED");
        else if ("ACTIVE".equals(row.getState())) {
            one(calls.participantState(id, actor, "ACCEPTED", "LEFT"));
            if (count(calls.participants(id), "ACCEPTED") < 2) end(row, "WEBSITE_ENDED");
            else {
                one(calls.advance(id, row.getVersion(), "ACTIVE", null));
                retire(row, row.getVersion() + 1, self.getMediaIdentity());
            }
        }
        sync.changed(people.stream().map(NativeCallParticipant::getUserId).toList());
        return true;
    }

    /** 原UUID恢复原应用版本并附当前快照，旧ACCEPT收据不把已经挂断的通话复活。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public Result command(
        long actor,
        String websiteScope,
        String callId,
        String requestId,
        long expectedVersion,
        String type
    ) {
        positive(actor);
        scope(websiteScope);
        uuid(requestId);
        if (expectedVersion < 1 || type == null || !COMMANDS.contains(type)) throw new IllegalArgumentException(
            "通话命令或版本无效"
        );
        NativeCall row = load(actor, callId);
        var people = calls.participants(callId);
        var self = person(people, actor);
        String fingerprint = hash(type + "|" + expectedVersion + "|" + websiteScope);
        var prior = calls.command(callId, actor, requestId);
        if (prior != null) {
            if (!fingerprint.equals(prior.getRequestHash())) throw conflict(
                "同一个通话命令UUID不能更换操作、版本或会话"
            );
            return new Result(requestId, prior.getAppliedVersion(), view(row, people));
        }
        if (type.equals("END") && actor != row.getInitiatorId()) throw new ForbiddenOperationException(
            "只有发起者可以结束所有人的通话"
        );
        // 群内其他人接听可先推进全局版本，但不消费本人的邀请。仅允许仍然待接听的
        // 本人按原明确意图接听；下方仍核验响铃期限、成员UUID、联系授权及所有有效租约。
        // 同一人的跨网站抢接、未来版本和其他动作不放宽，原UUID收据也保留原请求版本。
        boolean independentAcceptance =
            type.equals("ACCEPT") &&
            row.getVersion() > expectedVersion &&
            row.getState().equals("ACTIVE") &&
            self.getState().equals("INVITED");
        if (row.getVersion() != expectedVersion && !independentAcceptance) throw conflict("通话状态已变化，请先刷新");
        if (row.getState().equals("ENDED")) {
            if (!type.equals("LEAVE") && !type.equals("END")) throw conflict("通话已经结束");
        } else {
            if (row.getState().equals("RINGING") && !row.isRingFresh()) throw conflict("来电已过期，请刷新状态");
            if (type.equals("ACCEPT")) {
                lockContacts(row, people);
                if (people.stream().anyMatch(p -> p.getState().equals("ACCEPTED") && !p.isLeaseFresh())) throw conflict(
                    "发起方或通话连接已过期，请先刷新"
                );
                if (!calls.eligible(callId)) throw new ForbiddenOperationException("通话成员或联系权限已变化");
                if (!self.getState().equals("INVITED") || !row.isRingFresh()) throw conflict("来电已处理或过期");
                safety.requireContactAllowed(row.getInitiatorId(), actor);
                one(calls.accept(callId, actor, websiteScope, UUID.randomUUID().toString()));
                one(calls.advance(callId, row.getVersion(), "ACTIVE", null));
            } else if (type.equals("DECLINE")) {
                if (!self.getState().equals("INVITED")) throw conflict("只能拒接尚未处理的邀请");
                one(calls.participantState(callId, actor, "INVITED", "DECLINED"));
                var remaining = calls.participants(callId);
                if (count(remaining, "ACCEPTED") < 2 && count(remaining, "INVITED") == 0) end(row, "DECLINED");
                else one(calls.advance(callId, row.getVersion(), row.getState(), null));
            } else {
                if (
                    !self.getState().equals("ACCEPTED") || !websiteScope.equals(self.getWebsiteScope())
                ) throw new ForbiddenOperationException("只能从已接听的网站会话离开或结束通话");
                if (type.equals("END") || row.getState().equals("RINGING")) end(
                    row,
                    type.equals("END") ? "ENDED_BY_INITIATOR" : "CANCELLED"
                );
                else {
                    one(calls.participantState(callId, actor, "ACCEPTED", "LEFT"));
                    if (count(calls.participants(callId), "ACCEPTED") < 2) end(row, "COMPLETED");
                    else {
                        one(calls.advance(callId, row.getVersion(), "ACTIVE", null));
                        retire(row, row.getVersion() + 1, self.getMediaIdentity());
                    }
                }
            }
            row = calls.lock(callId);
            people = calls.participants(callId);
            sync.changed(people.stream().map(NativeCallParticipant::getUserId).toList());
        }
        var receipt = new NativeCallCommand();
        receipt.setCallId(callId);
        receipt.setActorId(actor);
        receipt.setRequestId(requestId);
        receipt.setCommandType(type);
        receipt.setRequestHash(fingerprint);
        receipt.setAppliedVersion(row.getVersion());
        one(calls.insertCommand(receipt));
        return new Result(requestId, row.getVersion(), view(row, people));
    }

    /** 心跳不能恢复已过期租约，也不会延长45秒响铃截止；不代表真实RTP仍在流动。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public void heartbeat(long actor, String websiteScope, String callId) {
        scope(websiteScope);
        NativeCall row = load(actor, callId);
        lockContacts(row, calls.participants(callId));
        if (row.getState().equals("ENDED") || !calls.eligible(callId)) throw conflict("通话已结束或权限失效");
        one(calls.heartbeat(callId, actor, websiteScope));
    }

    /** 内部对账：成员/拉黑失权结束；离线者退出，群内余下至少两人可继续。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, timeout = 5)
    public void reconcile(String callId) {
        uuid(callId);
        var initial = calls.selectById(callId);
        if (initial == null) return;
        var conversation = conversations.lock(initial.getConversationId());
        var row = calls.lock(callId);
        if (row.getState().equals("ENDED")) return;
        var people = calls.participants(callId);
        if (conversation == null || !"ACTIVE".equals(conversation.getStatus()) || !calls.eligible(callId)) end(
            row,
            "PERMISSION_CHANGED"
        );
        else if (row.getState().equals("RINGING") && !row.isRingFresh()) end(row, "NO_ANSWER");
        else {
            var departed = new ArrayList<NativeCallParticipant>();
            boolean changed = false;
            for (var person : people) {
                if (person.getState().equals("ACCEPTED") && !person.isLeaseFresh()) {
                    one(calls.participantState(callId, person.getUserId(), "ACCEPTED", "LEFT"));
                    departed.add(person);
                    changed = true;
                } else if (person.getState().equals("INVITED") && !row.isRingFresh()) {
                    one(calls.participantState(callId, person.getUserId(), "INVITED", "MISSED"));
                    changed = true;
                }
            }
            if (!changed) return;
            var remaining = calls.participants(callId);
            if (
                count(remaining, "ACCEPTED") == 0 ||
                (row.getState().equals("ACTIVE") && count(remaining, "ACCEPTED") < 2)
            ) end(row, "PEER_OFFLINE");
            else {
                one(calls.advance(callId, row.getVersion(), row.getState(), null));
                for (var person : departed) retire(row, row.getVersion() + 1, person.getMediaIdentity());
            }
        }
        sync.changed(people.stream().map(NativeCallParticipant::getUserId).toList());
    }

    /** 有界轮询扫描，重启只重置扫描游标，不重置通话控制事实。 */
    @Transactional(readOnly = true)
    public List<String> scan(String after) {
        if (after != null) uuid(after);
        return calls.scan(after);
    }

    private NativeCall load(long actor, String id) {
        positive(actor);
        uuid(id);
        var initial = calls.selectById(id);
        if (initial == null) throw new ResourceNotFoundException("通话不存在");
        active(conversations.lock(initial.getConversationId()));
        var row = calls.lock(id);
        authorized(row, actor);
        return row;
    }

    private void authorized(NativeCall row, long actor) {
        Member current = members.currentMember(row.getConversationId(), actor);
        var person = calls
            .participants(row.getId())
            .stream()
            .filter(p -> p.getUserId() == actor)
            .findFirst()
            .orElse(null);
        if (
            current == null || person == null || !current.getId().equals(person.getMemberId())
        ) throw new ResourceNotFoundException("通话不存在或已无权访问");
    }

    /** 联系锁全局排序且先于同步版本；READ_COMMITTED避免最初定位查询带入旧RR授权快照。 */
    private void lockContacts(NativeCall row, List<NativeCallParticipant> people) {
        long initiator = row.getInitiatorId();
        people
            .stream()
            .filter(
                p -> p.getUserId() != initiator && (p.getState().equals("INVITED") || p.getState().equals("ACCEPTED"))
            )
            .map(NativeCallParticipant::getUserId)
            .sorted(
                Comparator.comparingLong((Long id) -> Math.min(id, initiator)).thenComparingLong(id ->
                    Math.max(id, initiator)
                )
            )
            .forEach(id -> safety.requireContactAllowed(initiator, id));
    }

    private void end(NativeCall row, String reason) {
        one(calls.advance(row.getId(), row.getVersion(), "ENDED", reason));
        calls.finishParticipants(row.getId());
        retire(row, row.getVersion() + 1, null);
    }

    private void retire(NativeCall row, long version, String identity) {
        String action = identity == null ? "ROOM" : "PARTICIPANT";
        one(
            calls.retire(
                row.getId() + ":" + (identity == null ? "ROOM" : identity) + ":" + version,
                row.getId(),
                version,
                action,
                identity
            )
        );
    }

    private static View view(NativeCall row, List<NativeCallParticipant> people) {
        return new View(
            row.getId(),
            row.getConversationId(),
            String.valueOf(row.getInitiatorId()),
            row.getState(),
            row.getVersion(),
            row.getEndReason(),
            row.getRingExpiresAt(),
            people
                .stream()
                .map(p -> new Participant(String.valueOf(p.getUserId()), p.getDisplayName(), p.getState()))
                .toList()
        );
    }

    private static Member member(List<Member> values, long id) {
        return values
            .stream()
            .filter(v -> v.getUserId() == id)
            .findFirst()
            .orElseThrow(() -> new ForbiddenOperationException("只能邀请当前聊天成员"));
    }

    private static NativeCallParticipant person(List<NativeCallParticipant> values, long id) {
        return values
            .stream()
            .filter(v -> v.getUserId() == id)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("通话不存在"));
    }

    private static long count(List<NativeCallParticipant> values, String state) {
        return values
            .stream()
            .filter(v -> v.getState().equals(state))
            .count();
    }

    private static void active(cn.kokonexus.chat.domain.Conversation value) {
        if (value == null || !"ACTIVE".equals(value.getStatus())) throw new ResourceNotFoundException(
            "聊天会话不存在或已经关闭"
        );
    }

    private static void positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("用户标识无效");
    }

    private static void scope(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("网站会话作用域无效");
    }

    private static void uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(
            "通话UUID无效"
        );
    }

    private static void one(int rows) {
        if (rows != 1) throw conflict("通话变更未确认，请查询原请求");
    }

    private static IllegalStateException conflict(String message) {
        return new IllegalStateException(message);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** 对外状态是同意/控制事实，不含媒体凭据、网站摘要或已连接声音的推断。 */
    public record View(
        @Schema(description = "通话UUID") String id,
        @Schema(description = "所属聊天UUID") String conversationId,
        @Schema(description = "发起者平台ID字符串") String initiatorId,
        @Schema(description = "RINGING/ACTIVE/ENDED；ACTIVE不证明媒体连接") String state,
        @Schema(description = "当前单调控制版本") long version,
        @Schema(description = "固定结束原因，可空") String endReason,
        @Schema(description = "UTC响铃截止时间") LocalDateTime ringExpiresAtUtc,
        @Schema(description = "明确邀请的参与者，最多8人") List<Participant> participants
    ) {}

    public record Participant(
        @Schema(description = "平台ID字符串") String userId,
        @Schema(description = "显示名快照") String displayName,
        @Schema(description = "邀请/同意/退出状态，不代表开麦") String state
    ) {}

    public record Result(
        @Schema(description = "原命令UUID") String requestId,
        @Schema(description = "此命令提交时的版本") long appliedVersion,
        @Schema(description = "当前快照，不返回可能过时的已接听状态") View current
    ) {}

    /** 仅内部签名器使用的短事务投影，不作为HTTP响应或日志内容。 */
    public record MediaGrant(
        /** 原私人通话UUID。 */ String callId,
        /** 本人接听网站的随机媒体身份。 */ String identity,
        /** 成员显示名快照，最长80字。 */ String displayName
    ) {
        @Override
        public String toString() {
            return "NativeCallMediaGrant[redacted]";
        }
    }
}
