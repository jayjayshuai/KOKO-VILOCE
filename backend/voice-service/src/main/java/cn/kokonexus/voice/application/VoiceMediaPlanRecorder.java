package cn.kokonexus.voice.application;

import cn.kokonexus.voice.domain.VoiceInteraction.*;
import cn.kokonexus.voice.domain.VoiceMediaPlan.*;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 房间锁与既有核心事务内记录授权轮次/旧身份退场，绝不在这里访问LiveKit。 */
@Component
public class VoiceMediaPlanRecorder {

    /** 锁后仅返回当前匹配的旧网站绑定，迟到注销不能选择新轮次。 */
    public Binding websiteBinding(long room, long user, String scope) {
        requireTransaction();
        if (!enabled || !cn.kokonexus.api.voice.WebsiteSessionScope.valid(scope)) return null;
        var binding = media.binding(room, user);
        return binding != null &&
            "ACTIVE".equals(binding.getBindingState()) &&
            scope.equals(binding.getWebsiteSessionHash())
            ? binding
            : null;
    }

    /** 换网站会话推进UUID并写不可变旧目标；后续签发须等待退场屏障。 */
    public void rotateWebsite(VoiceRoom room, Binding binding, String scope) {
        requireTransaction();
        if (!enabled || !cn.kokonexus.api.voice.WebsiteSessionScope.valid(scope)) throw new IllegalStateException(
            "网站媒体绑定不可用"
        );
        rotate(
            room,
            binding,
            binding.getSessionId(),
            true,
            Boolean.TRUE.equals(binding.getPublishDesired()),
            binding.getSeatNo(),
            scope
        );
    }

    /** 当前旧摘要已核对；登记旧目标并失效，SQL事务内不调用SFU。 */
    public void revokeWebsite(VoiceRoom room, Binding binding) {
        requireTransaction();
        rotate(room, binding, binding.getSessionId(), false, false, null);
    }

    /** 当前成员/麦位，调用者已持房间锁。 */ private final VoiceInteractionMapper core;
    /** 媒体绑定/不可变退场目标。 */ private final VoiceMediaPlanMapper media;
    /** SQL计划候选默认关闭，不等于媒体接入开关。 */ private final boolean enabled;

    public VoiceMediaPlanRecorder(
        VoiceInteractionMapper core,
        VoiceMediaPlanMapper media,
        @Value("${koko.voice.media-plan-enabled:false}") boolean enabled
    ) {
        this.core = core;
        this.media = media;
        this.enabled = enabled;
    }

    /** 先消除失效绑定，再同步最多100名有效成员；任一失败令核心命令/审计/收据整事务回滚。 */
    public void reconcile(VoiceRoom room) {
        if (!enabled || !"CONTROLLED".equals(room.getControlMode())) return;
        requireTransaction();
        LocalDateTime now = core.databaseNow();
        var members = core.activeMembers(room.getId());
        var seats = core.seats(room.getId());
        var existing = media.activeBindings(room.getId());
        if (
            members.size() > 100 ||
            existing.size() > 100 ||
            (seats.size() != 8 && !(seats.isEmpty() && members.isEmpty() && existing.isEmpty()))
        ) throw new IllegalStateException("媒体计划事实超界");
        var active = new HashMap<Long, Member>();
        if ("OPEN".equals(room.getStatus())) for (var member : members)
            if ("ACTIVE".equals(member.getMemberState()) && member.getLeaseUntil().isAfter(now)) active.put(
                member.getUserId(),
                member
            );
        for (var binding : existing)
            if (!active.containsKey(binding.getUserId())) rotate(
                room,
                binding,
                binding.getSessionId(),
                false,
                false,
                null
            );
        var byUser = new HashMap<Long, Binding>();
        if (!active.isEmpty()) for (var binding : media.bindingsForUsers(
            room.getId(),
            active.keySet().stream().sorted().toList()
        ))
            byUser.put(binding.getUserId(), binding);
        for (var member : active.values()) {
            Seat seat = seats
                .stream()
                .filter(
                    s ->
                        "ON_MIC".equals(s.getSeatState()) &&
                        member.getUserId().equals(s.getUserId()) &&
                        member.getSessionId().equals(s.getSessionId())
                )
                .findFirst()
                .orElse(null);
            Integer seatNo = seat == null ? null : seat.getSeatNo();
            boolean publish = seat != null && Boolean.FALSE.equals(seat.getMuted());
            Binding binding = byUser.get(member.getUserId());
            if (binding == null) {
                binding = new Binding();
                binding.setRoomId(room.getId());
                binding.setUserId(member.getUserId());
                binding.setGeneration(1L);
                binding.setSessionId(member.getSessionId());
                binding.setMediaIdentity(UUID.randomUUID().toString());
                binding.setBindingState("ACTIVE");
                binding.setPublishDesired(publish);
                binding.setSeatNo(seatNo);
                one(media.insertBinding(binding));
            } else if (
                !"ACTIVE".equals(binding.getBindingState()) ||
                !member.getSessionId().equals(binding.getSessionId()) ||
                !Objects.equals(publish, binding.getPublishDesired()) ||
                !Objects.equals(seatNo, binding.getSeatNo())
            ) rotate(room, binding, member.getSessionId(), true, publish, seatNo);
        }
    }

    private void rotate(
        VoiceRoom room,
        Binding binding,
        String session,
        boolean active,
        boolean publish,
        Integer seat
    ) {
        rotate(room, binding, session, active, publish, seat, null);
    }

    private void rotate(
        VoiceRoom room,
        Binding binding,
        String session,
        boolean active,
        boolean publish,
        Integer seat,
        String scope
    ) {
        long old = binding.getGeneration(),
            next = Math.addExact(old, 1);
        if ("ACTIVE".equals(binding.getBindingState())) {
            var job = new Retirement();
            job.setId(UUID.randomUUID().toString());
            job.setRoomId(room.getId());
            job.setUserId(binding.getUserId());
            job.setGeneration(old);
            job.setProviderRoomName(room.getProviderRoomName());
            job.setMediaIdentity(binding.getMediaIdentity());
            one(media.insertRetirement(job));
        }
        binding.setGeneration(next);
        binding.setSessionId(session);
        binding.setMediaIdentity(UUID.randomUUID().toString());
        binding.setBindingState(active ? "ACTIVE" : "INACTIVE");
        binding.setPublishDesired(active && publish);
        binding.setSeatNo(active ? seat : null);
        binding.setWebsiteSessionHash(scope);
        one(media.rotateBinding(binding, old));
    }

    /** 调用者已当前授权；不返回随机身份/会话，未启用不是零任务成功。 */
    public cn.kokonexus.voice.interfaces.VoiceInteractionViews.MediaPlanView view(long room, long user) {
        if (!enabled) return new cn.kokonexus.voice.interfaces.VoiceInteractionViews.MediaPlanView(
            false,
            null,
            false,
            false,
            null,
            null,
            false
        );
        requireTransaction();
        var binding = media.binding(room, user);
        return new cn.kokonexus.voice.interfaces.VoiceInteractionViews.MediaPlanView(
            true,
            binding == null ? null : binding.getGeneration().toString(),
            binding != null && "ACTIVE".equals(binding.getBindingState()),
            binding != null && Boolean.TRUE.equals(binding.getPublishDesired()),
            media.pending(room),
            media.dead(room),
            false
        );
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException(
            "媒体计划必须与核心同事务"
        );
    }

    private static void one(int changed) {
        if (changed != 1) throw new IllegalStateException("媒体计划变更未确认");
    }
}
