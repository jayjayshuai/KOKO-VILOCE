package cn.kokonexus.voice.application;

import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.voice.domain.VoiceInteraction.*;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import cn.kokonexus.voice.interfaces.VoiceInteractionViews.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 房间行锁串行同房事实，RC避免不同房间的空范围间隙锁互等；不在事务中访问RPC/SFU。 */
@Service
public class VoiceInteractionService {

    /** 内部注销补偿：匹配网站摘要后才清理成员/麦位/审计及旧身份任务，迟到调用不动新绑定。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void retireWebsiteSession(long roomId, long user, String scope) {
        requireEnabled();
        identity(user, roomId);
        VoiceRoom room = mapper.lockRoom(roomId);
        if (room == null || !"CONTROLLED".equals(room.getControlMode())) return;
        var binding = media.websiteBinding(roomId, user, scope);
        if (binding == null) return;
        var member = mapper.member(roomId, user);
        boolean changed =
            member != null &&
            "ACTIVE".equals(member.getMemberState()) &&
            binding.getSessionId().equals(member.getSessionId());
        if (changed) {
            for (Seat seat : mapper.seats(roomId))
                if (Objects.equals(seat.getUserId(), user) && binding.getSessionId().equals(seat.getSessionId())) {
                    if (seat.getRequestId() != null) one(mapper.finishRequest(seat.getRequestId(), "CANCELLED"));
                    clear(seat);
                    one(mapper.saveSeat(seat));
                }
            one(mapper.setMemberState(roomId, user, "LEFT"));
            bump(room);
            one(
                mapper.audit(
                    UUID.randomUUID().toString(),
                    roomId,
                    user,
                    "WEBSITE_LOGOUT",
                    room.getInteractionVersion(),
                    mapper.databaseNow(),
                    user,
                    null,
                    null,
                    null
                )
            );
        }
        media.revokeWebsite(room, binding);
    }

    /** 授权先于审计查询，每次当前角色、版本游标有界。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ActionPage actions(long user, long roomId, String before, int size) {
        requireEnabled();
        identity(user, roomId);
        if (size < 1 || size > 50) throw new IllegalArgumentException("审计分页无效");
        Long cursor = before == null ? null : version(before);
        if (cursor != null && cursor == 0) throw new IllegalArgumentException("审计游标无效");
        VoiceRoom room = mapper.lockRoom(roomId);
        if (
            room == null ||
            !"CONTROLLED".equals(room.getControlMode()) ||
            !Set.of("OPEN", "CLOSING", "CLOSED").contains(room.getStatus())
        ) throw new ResourceNotFoundException("受控房间审计不存在");
        LocalDateTime now = "OPEN".equals(room.getStatus()) ? prepare(room) : mapper.databaseNow();
        Member viewer = mapper.member(roomId, user);
        if (
            user != room.getOwnerId() &&
            (!"OPEN".equals(room.getStatus()) || !active(viewer, now) || !management(viewer))
        ) throw new ForbiddenOperationException("仅房主或现任有效房管可读审计");
        var found = mapper.actions(roomId, cursor, size + 1);
        var items = found
            .subList(0, Math.min(size, found.size()))
            .stream()
            .map(a ->
                new ActionView(
                    a.getResultVersion().toString(),
                    a.getActorId() == null ? null : a.getActorId().toString(),
                    a.getCommandType(),
                    a.getTargetUserId() == null ? null : a.getTargetUserId().toString(),
                    a.getSeatNo(),
                    a.getSeatRequestId(),
                    a.getDesiredValue(),
                    a.getCreatedAt()
                )
            )
            .toList();
        return new ActionPage(items, found.size() > size ? items.getLast().version() : null);
    }

    /** 生产XML事实与当前读。 */
    private final VoiceInteractionMapper mapper;
    /** 候选核心开关，默认关闭；媒体准备度始终另行判断。 */
    private final boolean enabled;
    /** 同事务媒体轮次/退场记录器，不调用SFU。 */ private final VoiceMediaPlanRecorder media;

    public VoiceInteractionService(
        VoiceInteractionMapper mapper,
        @Value("${koko.voice.interaction-core-enabled:false}") boolean enabled,
        VoiceMediaPlanRecorder media
    ) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.media = media;
    }

    public void requireEnabled() {
        if (!enabled) throw new ExternalDependencyUnavailableException("语音房互动核心尚未开放", null);
    }

    /** 后台仅回收当前OPEN受控房间；与用户命令使用同一房间锁/审计/媒体退场事务。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reapExpiredMembers(long roomId) {
        requireEnabled();
        if (roomId <= 0) throw new IllegalArgumentException("回收房间标识无效");
        VoiceRoom room = mapper.lockRoom(roomId);
        if (room == null || !"OPEN".equals(room.getStatus()) || !"CONTROLLED".equals(room.getControlMode())) return;
        prepare(room);
    }

    /** 只返回候选开关，媒体准备度不能由配置伪造为true。 */
    public Capabilities features() {
        return new Capabilities(enabled, false, null, false);
    }

    /** 与核心快照相同的当前授权，再返回本人计划与房间队列的诊断。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MediaPlanView mediaPlan(long user, long roomId) {
        requireEnabled();
        identity(user, roomId);
        VoiceRoom room = mapper.lockRoom(roomId);
        if (
            room == null ||
            !"CONTROLLED".equals(room.getControlMode()) ||
            !Set.of("OPEN", "CLOSING", "CLOSED").contains(room.getStatus())
        ) throw new ResourceNotFoundException("受控媒体计划不存在");
        LocalDateTime now = "OPEN".equals(room.getStatus()) ? prepare(room) : mapper.databaseNow();
        if (
            user != room.getOwnerId() && (!"OPEN".equals(room.getStatus()) || !active(mapper.member(roomId, user), now))
        ) throw new ForbiddenOperationException("仅当前成员或房主可读媒体计划，关闭后仅房主");
        return media.view(roomId, user);
    }

    /** 只给版本/能力，不向未加入者展示成员或申请。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Capabilities capabilities(long user, long roomId) {
        identity(user, roomId);
        if (!enabled) return new Capabilities(false, false, null, false);
        VoiceRoom room = open(roomId);
        LocalDateTime now = prepare(room);
        return new Capabilities(
            true,
            false,
            room.getInteractionVersion().toString(),
            user == room.getOwnerId() || active(mapper.member(roomId, user), now)
        );
    }

    /** 名称必须由Controller外的身份目录提供；新UUID幂等绑定版本，不复活过期成员。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Ack join(long user, long roomId, String requestId, String expectedVersion, String displayName) {
        requireEnabled();
        identity(user, roomId);
        uuid(requestId);
        long expected = version(expectedVersion);
        if (
            displayName == null || displayName.isBlank() || displayName.length() > 80
        ) throw new IllegalArgumentException("成员名称无效");
        String fingerprint = digest("JOIN", expectedVersion);
        VoiceRoom room = mapper.lockRoom(roomId);
        if (room == null) throw new ResourceNotFoundException("语音房不存在");
        Receipt original = original(roomId, user, requestId, fingerprint);
        if (original != null) {
            Member member = mapper.member(roomId, user);
            if (
                !active(member, mapper.databaseNow()) || !original.getResultSessionId().equals(member.getSessionId())
            ) throw new IllegalStateException("原入房会话已失效，请使用新请求重新加入");
            return ack(original);
        }
        controlled(room);
        LocalDateTime now = prepare(room);
        expected(room, expected);
        budget(roomId, user, now, false);
        Member old = mapper.member(roomId, user);
        if (active(old, now)) throw new IllegalStateException("本人已有有效房间会话，请先退出或恢复原会话");
        List<Member> members = mapper.activeMembers(roomId);
        if (members.size() >= room.getMaxParticipants()) throw new IllegalStateException("房间成员租约已满");
        Member member = new Member();
        member.setRoomId(roomId);
        member.setUserId(user);
        member.setDisplayName(displayName);
        member.setRoomRole(
            user == room.getOwnerId()
                ? "OWNER"
                : old != null && "ADMIN".equals(old.getRoomRole())
                  ? "ADMIN"
                  : "LISTENER"
        );
        member.setMemberState("ACTIVE");
        member.setSessionId(UUID.randomUUID().toString());
        member.setLeaseUntil(now.plusSeconds(90));
        int changed = mapper.saveMember(member, now);
        if (changed != 1 && changed != 2) throw new IllegalStateException("成员登记未确认");
        return commit(room, user, requestId, fingerprint, "JOIN", member.getSessionId(), now, user, null, null, null);
    }

    /** 每25秒续约建议；旧轮次或过期租约不允许复活，不增加命令/审计预算。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void heartbeat(long user, long roomId, String sessionId) {
        requireEnabled();
        identity(user, roomId);
        uuid(sessionId);
        VoiceRoom room = open(roomId);
        LocalDateTime now = prepare(room);
        member(roomId, user, sessionId, now);
        one(mapper.touchMember(roomId, user, sessionId, now, now.plusSeconds(90)));
    }

    /** 房主可未加入观察；其他人仅有效成员，收据不当当前快照。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Snapshot snapshot(long user, long roomId) {
        requireEnabled();
        identity(user, roomId);
        VoiceRoom room = open(roomId);
        LocalDateTime now = prepare(room);
        Member viewer = mapper.member(roomId, user);
        if (!active(viewer, now) && user != room.getOwnerId()) throw new ForbiddenOperationException("请先加入房间");
        return projectSnapshot(room, viewer, user, now);
    }

    /** 同版本也先锁房间、回收租约、核对当前成员；不返回旧权限下的快照。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SyncView sync(long user, long roomId, String knownVersion) {
        requireEnabled();
        identity(user, roomId);
        Long known = knownVersion == null ? null : version(knownVersion);
        VoiceRoom room = open(roomId);
        LocalDateTime now = prepare(room);
        Member viewer = mapper.member(roomId, user);
        if (!active(viewer, now) && user != room.getOwnerId()) throw new ForbiddenOperationException("请先加入房间");
        return new SyncView(
            room.getInteractionVersion().toString(),
            now,
            Objects.equals(known, room.getInteractionVersion()) ? null : projectSnapshot(room, viewer, user, now)
        );
    }

    /** 用原UUID核对本人提交；房间锁后当前读，允许离房/关闭后核对原事实，不登记或续约。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ReceiptView receipt(long user, long roomId, String requestId) {
        requireEnabled();
        identity(user, roomId);
        uuid(requestId);
        VoiceRoom room = mapper.lockRoom(roomId);
        if (room == null || !"CONTROLLED".equals(room.getControlMode())) throw new ResourceNotFoundException(
            "受控房间不存在"
        );
        Receipt original = mapper.receipt(roomId, user, requestId);
        return new ReceiptView(original != null, original == null ? null : ack(original));
    }

    /** 已在同一事务完成当前授权；成员/管理员/预约均使用当前读且有界。 */
    private Snapshot projectSnapshot(VoiceRoom room, Member viewer, long user, LocalDateTime now) {
        long roomId = room.getId();
        List<Member> members = mapper.activeMembers(roomId);
        List<Seat> seats = mapper.seats(roomId);
        boolean manager = user == room.getOwnerId() || (active(viewer, now) && management(viewer));
        return new Snapshot(
            Long.toString(roomId),
            room.getInteractionVersion().toString(),
            now,
            90,
            active(viewer, now) ? viewer.getSessionId() : null,
            active(viewer, now) ? viewer.getRoomRole() : null,
            false,
            members
                .stream()
                .map(m -> {
                    Seat occupied = seats
                        .stream()
                        .filter(s -> Objects.equals(s.getUserId(), m.getUserId()))
                        .findFirst()
                        .orElse(null);
                    String role =
                        "LISTENER".equals(m.getRoomRole()) &&
                        occupied != null &&
                        "ON_MIC".equals(occupied.getSeatState())
                            ? "HOST"
                            : m.getRoomRole();
                    return new MemberView(
                        m.getUserId().toString(),
                        m.getDisplayName(),
                        role,
                        occupied == null ? null : occupied.getSeatNo()
                    );
                })
                .toList(),
            manager
                ? mapper
                      .administrators(roomId)
                      .stream()
                      .map(m -> new MemberView(m.getUserId().toString(), m.getDisplayName(), "ADMIN", null))
                      .toList()
                : List.of(),
            seats
                .stream()
                .map(s ->
                    new SeatView(
                        s.getSeatNo(),
                        s.getSeatState(),
                        s.getUserId() == null ? null : s.getUserId().toString(),
                        s.getMuted()
                    )
                )
                .toList(),
            mapper
                .pendingRequests(roomId)
                .stream()
                .filter(r -> manager || r.getUserId() == user)
                .map(r ->
                    new RequestView(
                        r.getId(),
                        r.getSeatNo(),
                        r.getUserId().toString(),
                        r.getRequestType(),
                        r.getExpiresAt()
                    )
                )
                .toList()
        );
    }

    /** 全部输入参与指纹；同UUID重复不执行，旧版本不覆盖新状态。 */
    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Ack command(
        long user,
        long roomId,
        String requestId,
        String sessionId,
        String expectedVersion,
        CommandType type,
        Integer seatNo,
        Long targetUser,
        String seatRequestId,
        Boolean value
    ) {
        requireEnabled();
        identity(user, roomId);
        uuid(requestId);
        uuid(sessionId);
        long expected = version(expectedVersion);
        validate(type, seatNo, targetUser, seatRequestId, value);
        String fingerprint = digest(
            type.name(),
            sessionId,
            expectedVersion,
            String.valueOf(seatNo),
            String.valueOf(targetUser),
            String.valueOf(seatRequestId),
            String.valueOf(value)
        );
        VoiceRoom room = mapper.lockRoom(roomId);
        if (room == null) throw new ResourceNotFoundException("语音房不存在");
        Receipt original = original(roomId, user, requestId, fingerprint);
        if (original != null) return ack(original); // 已提交事实即使成员后来离开，也不能伪装没提交。
        controlled(room);
        LocalDateTime now = prepare(room);
        Member actor = member(roomId, user, sessionId, now);
        expected(room, expected);
        budget(roomId, user, now, type == CommandType.LEAVE);
        List<Seat> seats = mapper.seats(roomId);
        Seat seat = seatNo == null ? null : seats.get(seatNo - 1);
        Long auditTarget = targetUser;
        Integer auditSeat = seatNo;
        String auditRequest = seatRequestId;
        switch (type) {
            case APPLY, INVITE, PULL -> {
                Member target = type == CommandType.APPLY ? actor : member(roomId, targetUser, null, now);
                auditTarget = target.getUserId();
                if (type != CommandType.APPLY) manager(actor);
                if (
                    !"EMPTY".equals(seat.getSeatState()) ||
                    seats.stream().anyMatch(s -> Objects.equals(s.getUserId(), target.getUserId()))
                ) throw new IllegalStateException("麦位已占用或成员已有麦位");
                seat.setUserId(target.getUserId());
                seat.setSessionId(target.getSessionId());
                seat.setMuted(true);
                if (type == CommandType.PULL) seat.setSeatState("ON_MIC");
                else {
                    SeatRequest request = new SeatRequest();
                    request.setId(UUID.randomUUID().toString());
                    request.setRoomId(roomId);
                    request.setSeatNo(seatNo);
                    request.setUserId(target.getUserId());
                    request.setSessionId(target.getSessionId());
                    request.setRequestType(type.name());
                    request.setExpiresAt(now.plusSeconds(60));
                    one(mapper.insertRequest(request, now));
                    auditRequest = request.getId();
                    seat.setRequestId(request.getId());
                    seat.setSeatState("RESERVED");
                }
                one(mapper.saveSeat(seat));
            }
            case ACCEPT, REJECT, CANCEL -> {
                SeatRequest request = mapper
                    .pendingRequests(roomId)
                    .stream()
                    .filter(r -> r.getId().equals(seatRequestId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("预约已结束或不存在"));
                Seat reserved = seats.get(request.getSeatNo() - 1);
                Member target = member(roomId, request.getUserId(), request.getSessionId(), now);
                auditTarget = target.getUserId();
                auditSeat = request.getSeatNo();
                if (
                    !Objects.equals(reserved.getRequestId(), request.getId()) ||
                    !Objects.equals(reserved.getSessionId(), target.getSessionId())
                ) throw new IllegalStateException("预约轮次已变化");
                if (
                    type == CommandType.CANCEL ||
                    ("INVITE".equals(request.getRequestType()) && type == CommandType.ACCEPT)
                ) {
                    if (user != target.getUserId()) throw new ForbiddenOperationException("仅本人确认或取消该预约");
                } else manager(actor);
                one(
                    mapper.finishRequest(
                        request.getId(),
                        type == CommandType.ACCEPT ? "ACCEPTED" : type == CommandType.REJECT ? "REJECTED" : "CANCELLED"
                    )
                );
                if (type == CommandType.ACCEPT) {
                    reserved.setSeatState("ON_MIC");
                    reserved.setRequestId(null);
                    reserved.setMuted(true);
                } else clear(reserved);
                one(mapper.saveSeat(reserved));
            }
            case DOWN, KICK, MUTE -> {
                if (!"ON_MIC".equals(seat.getSeatState())) throw new IllegalStateException("目标不在麦位");
                Member target = member(roomId, seat.getUserId(), seat.getSessionId(), now);
                auditTarget = target.getUserId();
                if (type == CommandType.DOWN && user != target.getUserId()) throw new ForbiddenOperationException(
                    "仅本人下麦"
                );
                if (type == CommandType.KICK || user != target.getUserId()) hierarchy(actor, target);
                if (type == CommandType.MUTE) seat.setMuted(value);
                else clear(seat);
                one(mapper.saveSeat(seat));
            }
            case LOCK -> {
                manager(actor);
                if (!Set.of("EMPTY", "LOCKED").contains(seat.getSeatState())) throw new IllegalStateException(
                    "占用麦位不能锁定或解锁"
                );
                seat.setSeatState(value ? "LOCKED" : "EMPTY");
                one(mapper.saveSeat(seat));
            }
            case LEAVE -> {
                auditTarget = user;
                for (Seat owned : seats)
                    if (Objects.equals(owned.getUserId(), user) && sessionId.equals(owned.getSessionId())) {
                        auditSeat = owned.getSeatNo();
                        auditRequest = owned.getRequestId();
                        if (owned.getRequestId() != null) one(mapper.finishRequest(owned.getRequestId(), "CANCELLED"));
                        clear(owned);
                        one(mapper.saveSeat(owned));
                    }
                one(mapper.setMemberState(roomId, user, "LEFT"));
            }
            case ADMIN, TRANSFER -> {
                if (
                    !"OWNER".equals(actor.getRoomRole()) || room.getOwnerId() != user
                ) throw new ForbiddenOperationException("仅房主可调整房间角色");
                Member target =
                    type == CommandType.TRANSFER
                        ? member(roomId, targetUser, null, now)
                        : mapper.member(roomId, targetUser);
                if (target == null) throw new ResourceNotFoundException("目标未曾加入该房间");
                if (target.getUserId() == user) throw new IllegalArgumentException("不能调整本人房主身份");
                if (type == CommandType.ADMIN) {
                    if (
                        value && !"ADMIN".equals(target.getRoomRole()) && mapper.administrators(roomId).size() >= 8
                    ) throw new IllegalStateException("最多八名长期房管");
                    one(mapper.setRole(roomId, targetUser, value ? "ADMIN" : "LISTENER"));
                } else {
                    if (
                        !"ADMIN".equals(target.getRoomRole()) && mapper.administrators(roomId).size() >= 8
                    ) throw new IllegalStateException("转让前请先减少一名房管");
                    one(mapper.setRole(roomId, user, "ADMIN"));
                    one(mapper.setRole(roomId, targetUser, "OWNER"));
                    one(mapper.transfer(roomId, user, target));
                }
            }
        }
        return commit(
            room,
            user,
            requestId,
            fingerprint,
            type.name(),
            null,
            now,
            auditTarget,
            auditSeat,
            auditRequest,
            value
        );
    }

    private VoiceRoom open(long id) {
        VoiceRoom room = mapper.lockRoom(id);
        if (room == null) throw new ResourceNotFoundException("语音房不存在");
        controlled(room);
        return room;
    }

    private void controlled(VoiceRoom room) {
        if (
            !"OPEN".equals(room.getStatus()) || !"CONTROLLED".equals(room.getControlMode())
        ) throw new ResourceNotFoundException("受控房间不存在或已关闭，原通话房间不转换");
    }

    /** 有界惰性回收，所有读采用锁后当前事实；失败整事务回滚。 */
    private LocalDateTime prepare(VoiceRoom room) {
        LocalDateTime now = mapper.databaseNow();
        List<Seat> seats = mapper.seats(room.getId());
        if (seats.isEmpty()) {
            for (int no = 1; no <= 8; no++) one(mapper.insertSeat(room.getId(), no));
            seats = mapper.seats(room.getId());
        }
        if (seats.size() != 8) throw new IllegalStateException("麦位数据异常，拒绝操作");
        List<Member> members = mapper.activeMembers(room.getId());
        List<SeatRequest> requests = mapper.pendingRequests(room.getId());
        if (
            members.size() > 100 || requests.size() > 8 || mapper.administrators(room.getId()).size() > 8
        ) throw new IllegalStateException("互动数据超出保护范围");
        Set<String> expired = new HashSet<>();
        boolean changed = false;
        for (Member m : members)
            if (!active(m, now)) {
                expired.add(m.getSessionId());
                one(mapper.setMemberState(room.getId(), m.getUserId(), "LEFT"));
                changed = true;
            }
        for (SeatRequest r : requests)
            if (!r.getExpiresAt().isAfter(now) || expired.contains(r.getSessionId())) {
                Seat s = seats.get(r.getSeatNo() - 1);
                if (
                    !r.getId().equals(s.getRequestId()) || !r.getSessionId().equals(s.getSessionId())
                ) throw new IllegalStateException("预约数据异常");
                one(mapper.finishRequest(r.getId(), "EXPIRED"));
                clear(s);
                one(mapper.saveSeat(s));
                changed = true;
            }
        for (Seat s : seats)
            if (s.getSessionId() != null && expired.contains(s.getSessionId())) {
                clear(s);
                one(mapper.saveSeat(s));
                changed = true;
            }
        media.reconcile(room);
        if (changed) {
            bump(room);
            one(
                mapper.audit(
                    UUID.randomUUID().toString(),
                    room.getId(),
                    null,
                    "EXPIRE",
                    room.getInteractionVersion(),
                    now,
                    null,
                    null,
                    null,
                    null
                )
            );
        }
        return now;
    }

    private Member member(long room, long user, String session, LocalDateTime now) {
        Member member = mapper.member(room, user);
        if (
            !active(member, now) || (session != null && !session.equals(member.getSessionId()))
        ) throw new ForbiddenOperationException("房间会话失效，请重新加入");
        return member;
    }

    private static boolean active(Member m, LocalDateTime now) {
        return (
            m != null &&
            "ACTIVE".equals(m.getMemberState()) &&
            m.getLeaseUntil() != null &&
            m.getLeaseUntil().isAfter(now)
        );
    }

    private static boolean management(Member m) {
        return Set.of("OWNER", "ADMIN").contains(m.getRoomRole());
    }

    private static void manager(Member m) {
        if (!management(m)) throw new ForbiddenOperationException("仅房主管理员可操作");
    }

    private static void hierarchy(Member actor, Member target) {
        manager(actor);
        if (
            "ADMIN".equals(actor.getRoomRole()) &&
            !"LISTENER".equals(target.getRoomRole()) &&
            !actor.getUserId().equals(target.getUserId())
        ) throw new ForbiddenOperationException("房管不能操作其他房管或房主");
    }

    private static void clear(Seat s) {
        s.setSeatState("EMPTY");
        s.setUserId(null);
        s.setSessionId(null);
        s.setRequestId(null);
        s.setMuted(true);
    }

    private Receipt original(long room, long user, String id, String fingerprint) {
        Receipt r = mapper.receipt(room, user, id);
        if (r != null && !fingerprint.equals(r.getFingerprint())) throw new IllegalStateException(
            "相同请求UUID不能更改参数"
        );
        return r;
    }

    private void budget(long room, long user, LocalDateTime now, boolean leaving) {
        if (!leaving && mapper.receiptBudget(room, user).size() >= 1000) throw new IllegalStateException(
            "本人房间命令记录达到保护上限"
        );
        if (
            !leaving && mapper.recentReceipts(room, user, now.minusSeconds(10)).size() >= 10
        ) throw new IllegalStateException("房间操作过快，请稍后重试");
    }

    private Ack commit(
        VoiceRoom room,
        long user,
        String id,
        String fingerprint,
        String type,
        String session,
        LocalDateTime now,
        Long target,
        Integer seat,
        String request,
        Boolean value
    ) {
        media.reconcile(room);
        bump(room);
        Receipt r = new Receipt();
        r.setFingerprint(fingerprint);
        r.setCommandType(type);
        r.setResultVersion(room.getInteractionVersion());
        r.setResultSessionId(session);
        one(
            mapper.audit(
                UUID.randomUUID().toString(),
                room.getId(),
                user,
                type,
                room.getInteractionVersion(),
                now,
                target,
                seat,
                request,
                value
            )
        );
        one(mapper.insertReceipt(room.getId(), user, id, r, now));
        return ack(r);
    }

    private void bump(VoiceRoom room) {
        one(mapper.bumpVersion(room.getId()));
        room.setInteractionVersion(Math.addExact(room.getInteractionVersion(), 1));
    }

    private static Ack ack(Receipt r) {
        return new Ack(r.getCommandType(), r.getResultVersion().toString(), r.getResultSessionId());
    }

    private static void expected(VoiceRoom r, long v) {
        if (r.getInteractionVersion() != v) throw new IllegalStateException("房间状态已变化，请刷新后使用新请求操作");
    }

    private static void identity(long user, long room) {
        if (user <= 0 || room <= 0) throw new IllegalArgumentException("身份或房间无效");
    }

    private static void uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(
            "请求或会话UUID无效"
        );
    }

    private static long version(String v) {
        if (v == null || !v.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("版本无效");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本超界");
        }
    }

    private static String digest(String... parts) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256不可用", impossible);
        }
    }

    private static void one(int changed) {
        if (changed != 1) throw new IllegalStateException("互动变更未确认");
    }

    private static void validate(CommandType type, Integer seat, Long target, String request, Boolean value) {
        if (type == null) throw new IllegalArgumentException("命令类型缺失");
        boolean usesSeat = Set.of(
            CommandType.APPLY,
            CommandType.INVITE,
            CommandType.PULL,
            CommandType.DOWN,
            CommandType.KICK,
            CommandType.MUTE,
            CommandType.LOCK
        ).contains(type);
        boolean usesTarget = Set.of(
            CommandType.INVITE,
            CommandType.PULL,
            CommandType.ADMIN,
            CommandType.TRANSFER
        ).contains(type);
        boolean usesRequest = Set.of(CommandType.ACCEPT, CommandType.REJECT, CommandType.CANCEL).contains(type);
        boolean usesValue = Set.of(CommandType.MUTE, CommandType.LOCK, CommandType.ADMIN).contains(type);
        if (usesSeat ? seat == null || seat < 1 || seat > 8 : seat != null) throw new IllegalArgumentException(
            "麦位参数无效"
        );
        if (usesTarget ? target == null || target <= 0 : target != null) throw new IllegalArgumentException(
            "目标成员参数无效"
        );
        if (usesRequest) uuid(request);
        else if (request != null) throw new IllegalArgumentException("多余预约参数");
        if (usesValue ? value == null : value != null) throw new IllegalArgumentException("闭麦/锁麦/角色参数无效");
    }
}
