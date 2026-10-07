package cn.kokonexus.voice.application;

import cn.kokonexus.voice.infrastructure.media.LiveKitJoinTokenVerifier.VerifiedJoin;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 准入只核对当前房间，SQL锁内不访问媒体或身份RPC；不把核验视为WebSocket全会话授权。 */
@Service
public class VoiceMediaAdmissionState {

    /** 与关闭意图/转让共用的当前房间行锁。 */ private final VoiceRoomMapper rooms;
    /** 当前成员租约/席位；不在其他库查身份。 */ private final VoiceInteractionMapper core;
    /** 当前媒体轮次/待退场事实，不用缓存替代撤权。 */ private final VoiceMediaPlanMapper media;
    /** 轮次准入候选默认关闭；仍不是受控令牌签发开关。 */ private final boolean bindingEnabled;

    public VoiceMediaAdmissionState(
        VoiceRoomMapper rooms,
        VoiceInteractionMapper core,
        VoiceMediaPlanMapper media,
        @Value("${koko.voice.binding-admission-enabled:false}") boolean bindingEnabled
    ) {
        this.rooms = rooms;
        this.core = core;
        this.media = media;
        this.bindingEnabled = bindingEnabled;
    }

    /** 已提交CLOSING即拒绝；CONTROLLED尚无完整媒体协议，始终拒绝旧发布凭据。 */
    @Transactional(timeout = 2, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean allows(VerifiedJoin join, long user, boolean entry) {
        var room = rooms.lockRoom(join.roomId());
        if (
            room == null ||
            !"OPEN".equals(room.getStatus()) ||
            !join.providerRoomName().equals(room.getProviderRoomName())
        ) return false;
        if ("LEGACY".equals(room.getControlMode())) return !join.binding();
        if (
            !bindingEnabled || !"CONTROLLED".equals(room.getControlMode()) || !join.binding() || user <= 0
        ) return false;
        var member = core.member(room.getId(), user);
        var now = core.databaseNow();
        if (
            member == null ||
            member.getUserId() != user ||
            !"ACTIVE".equals(member.getMemberState()) ||
            member.getLeaseUntil() == null ||
            !member.getLeaseUntil().isAfter(now)
        ) return false;
        var binding = media.binding(room.getId(), user);
        if (
            binding == null ||
            !"ACTIVE".equals(binding.getBindingState()) ||
            !member.getSessionId().equals(binding.getSessionId()) ||
            !join.identity().equals(binding.getMediaIdentity()) ||
            join.publish() != Boolean.TRUE.equals(binding.getPublishDesired())
        ) return false;
        var seats = core.seats(room.getId());
        if (seats.size() != 8) return false;
        var seat = seats
            .stream()
            .filter(
                s ->
                    "ON_MIC".equals(s.getSeatState()) &&
                    member.getUserId().equals(s.getUserId()) &&
                    member.getSessionId().equals(s.getSessionId())
            )
            .findFirst()
            .orElse(null);
        if (
            !java.util.Objects.equals(binding.getSeatNo(), seat == null ? null : seat.getSeatNo()) ||
            join.publish() != (seat != null && Boolean.FALSE.equals(seat.getMuted()))
        ) return false;
        return !entry || (media.pending(room.getId()) == 0 && media.dead(room.getId()) == 0);
    }
}
