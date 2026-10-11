package cn.kokonexus.voice.application;

import cn.kokonexus.common.api.*;
import cn.kokonexus.voice.infrastructure.persistence.*;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 房间锁下读取签发事实及绑定当前网站会话；SQL事务内不调用身份RPC或媒体API。 */
@Service
@RequiredArgsConstructor
public class VoiceMediaCredentialState {

    /** 与关闭/转让/麦位命令共用当前房间行锁。 */ private final VoiceRoomMapper rooms;
    /** 当前成员、席位和数据库租约时钟。 */ private final VoiceInteractionMapper core;
    /** 当前UUID绑定及持久退场屏障。 */ private final VoiceMediaPlanMapper media;
    /** 网站授权轮次写入，与核心使用相同事务/旧目标计划。 */ private final VoiceMediaPlanRecorder plans;

    @Transactional(timeout = 2, isolation = Isolation.READ_COMMITTED)
    public Grant current(long roomId, long user, String session, String version, String scope) {
        if (
            !cn.kokonexus.api.voice.WebsiteSessionScope.valid(scope) ||
            roomId <= 0 ||
            user <= 0 ||
            session == null ||
            !UUID.fromString(session).toString().equals(session) ||
            version == null ||
            !version.matches("0|[1-9][0-9]{0,18}")
        ) throw new IllegalArgumentException("媒体请求标识无效");
        long expected;
        try {
            expected = Long.parseLong(version);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("房间版本超界");
        }
        var room = rooms.lockRoom(roomId);
        if (
            room == null || !"OPEN".equals(room.getStatus()) || !"CONTROLLED".equals(room.getControlMode())
        ) throw new ResourceNotFoundException("受控房间不存在或已关闭");
        var member = core.member(roomId, user);
        var now = core.databaseNow();
        if (
            member == null ||
            !Objects.equals(user, member.getUserId()) ||
            !"ACTIVE".equals(member.getMemberState()) ||
            !session.equals(member.getSessionId()) ||
            member.getLeaseUntil() == null ||
            !member.getLeaseUntil().isAfter(now)
        ) throw new ForbiddenOperationException("当前成员会话不存在或已过期");
        if (room.getInteractionVersion() != expected) throw new IllegalStateException(
            "房间版本已变化，请重新核验后请求媒体凭据"
        );
        if (
            !("koko-voice-" + roomId).equals(room.getProviderRoomName()) ||
            member.getDisplayName() == null ||
            member.getDisplayName().isBlank() ||
            member.getDisplayName().length() > 80
        ) throw new ExternalDependencyUnavailableException("媒体房间或成员投影无效", null);
        var binding = media.binding(roomId, user);
        if (
            binding == null ||
            !"ACTIVE".equals(binding.getBindingState()) ||
            !session.equals(binding.getSessionId()) ||
            binding.getGeneration() == null ||
            binding.getGeneration() <= 0 ||
            binding.getMediaIdentity() == null ||
            !UUID.fromString(binding.getMediaIdentity()).toString().equals(binding.getMediaIdentity())
        ) throw new ExternalDependencyUnavailableException("当前媒体绑定尚未准备好", null);
        var seats = core.seats(roomId);
        if (seats.size() != 8) throw new ExternalDependencyUnavailableException("房间麦位事实不完整", null);
        var seat = seats
            .stream()
            .filter(
                s ->
                    "ON_MIC".equals(s.getSeatState()) &&
                    Objects.equals(user, s.getUserId()) &&
                    session.equals(s.getSessionId())
            )
            .findFirst()
            .orElse(null);
        boolean publish = seat != null && Boolean.FALSE.equals(seat.getMuted());
        if (
            !Objects.equals(binding.getSeatNo(), seat == null ? null : seat.getSeatNo()) ||
            publish != Boolean.TRUE.equals(binding.getPublishDesired())
        ) throw new ExternalDependencyUnavailableException("媒体绑定与当前麦位不一致", null);
        if (media.pending(roomId) != 0 || media.dead(roomId) != 0) throw new ExternalDependencyUnavailableException(
            "旧媒体退场未确认，暂不签发新凭据",
            null
        );
        boolean ready = true;
        if (binding.getWebsiteSessionHash() == null) {
            if (media.claimWebsite(roomId, user, binding.getGeneration(), scope) != 1) throw new IllegalStateException(
                "网站媒体绑定未确认"
            );
            binding.setWebsiteSessionHash(scope);
        } else if (!scope.equals(binding.getWebsiteSessionHash())) {
            plans.rotateWebsite(room, binding, scope);
            ready = false;
        }
        return new Grant(
            room.getProviderRoomName(),
            binding.getMediaIdentity(),
            member.getDisplayName(),
            session,
            binding.getGeneration().toString(),
            binding.getSeatNo(),
            publish,
            ready
        );
    }

    /** 内部投影不输出身份或会话日志。 */
    public record Grant(
        /** 唯一供应商房间，仅后端签发使用。 */ String roomName,
        /** 已核验随机媒体UUID，不含PII。 */ String identity,
        /** 服务器成员名称快照。 */ String name,
        /** 当前成员会话。 */ String sessionId,
        /** 不转JS number的单调授权轮次。 */ String generation,
        /** 当前占用麦位，听众为空。 */ Integer seatNo,
        /** 是否仅允许麦克风发布。 */ boolean publish,
        /** 新网站轮次登记已提交但旧退场未确认时为false，不能签JWT。 */ boolean ready
    ) {
        public Grant(
            String roomName,
            String identity,
            String name,
            String sessionId,
            String generation,
            Integer seatNo,
            boolean publish
        ) {
            this(roomName, identity, name, sessionId, generation, seatNo, publish, true);
        }

        @Override
        public String toString() {
            return "VoiceMediaGrant[redacted]";
        }
    }
}
