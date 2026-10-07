package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.common.api.*;
import cn.kokonexus.voice.domain.VoiceInteraction.*;
import cn.kokonexus.voice.domain.VoiceMediaPlan.Binding;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 当前绑定/席位/租约与待退场拒绝；实际XML与事务另由独立MySQL检查。 */
class VoiceMediaCredentialStateTest {

    /** 明确模拟持久事实边界，不使用该桩证明SQL正确。 */ private final VoiceRoomMapper rooms = mock(
        VoiceRoomMapper.class
    );
    /** 明确模拟当前成员/席位。 */ private final VoiceInteractionMapper core = mock(VoiceInteractionMapper.class);
    /** 明确模拟当前绑定和退场任务。 */ private final VoiceMediaPlanMapper media = mock(VoiceMediaPlanMapper.class);
    /** 真实授权用例，未开启事务代理。 */ private final VoiceMediaCredentialState state = new VoiceMediaCredentialState(
        rooms,
        core,
        media
    );
    /** 合成会话，不是登录凭据。 */ private final String session = UUID.randomUUID().toString();
    /** 合成数据库时钟。 */ private final LocalDateTime now = LocalDateTime.of(2026, 10, 7, 12, 0);

    private Binding fixture(boolean publisher) {
        var room = new VoiceRoom();
        room.setId(9L);
        room.setStatus("OPEN");
        room.setControlMode("CONTROLLED");
        room.setProviderRoomName("koko-voice-9");
        room.setInteractionVersion(3L);
        when(rooms.lockRoom(9)).thenReturn(room);
        var member = new Member();
        member.setUserId(42L);
        member.setSessionId(session);
        member.setMemberState("ACTIVE");
        member.setDisplayName("合成成员");
        member.setLeaseUntil(now.plusSeconds(90));
        when(core.member(9, 42)).thenReturn(member);
        when(core.databaseNow()).thenReturn(now);
        var seats = new ArrayList<Seat>();
        for (int i = 1; i <= 8; i++) {
            var s = new Seat();
            s.setSeatNo(i);
            s.setSeatState("EMPTY");
            s.setMuted(true);
            seats.add(s);
        }
        if (publisher) {
            var seat = seats.getFirst();
            seat.setSeatState("ON_MIC");
            seat.setUserId(42L);
            seat.setSessionId(session);
            seat.setMuted(false);
        }
        when(core.seats(9)).thenReturn(seats);
        var binding = new Binding();
        binding.setBindingState("ACTIVE");
        binding.setSessionId(session);
        binding.setGeneration(9007199254741001L);
        binding.setMediaIdentity(UUID.randomUUID().toString());
        binding.setSeatNo(publisher ? 1 : null);
        binding.setPublishDesired(publisher);
        when(media.binding(9, 42)).thenReturn(binding);
        return binding;
    }

    @Test
    void listenerAndCurrentPublisherUseOpaqueIdentityWithoutRoleBasedPrivilege() {
        var b = fixture(false);
        var grant = state.current(9, 42, session, "3");
        assertThat(grant.publish()).isFalse();
        assertThat(grant.seatNo()).isNull();
        assertThat(grant.identity()).isEqualTo(b.getMediaIdentity());
        assertThat(grant.generation()).isEqualTo("9007199254741001");
        assertThat(grant.toString()).doesNotContain(b.getMediaIdentity(), session);
        fixture(true);
        assertThat(state.current(9, 42, session, "3").publish()).isTrue();
    }

    @Test
    void expiredWrongSessionVersionAndUnconfirmedRetirementCannotMintGrant() {
        fixture(true);
        assertThatThrownBy(() -> state.current(9, 42, UUID.randomUUID().toString(), "3")).isInstanceOf(
            ForbiddenOperationException.class
        );
        assertThatThrownBy(() -> state.current(9, 42, session, "2")).isInstanceOf(IllegalStateException.class);
        when(media.pending(9)).thenReturn(1);
        assertThatThrownBy(() -> state.current(9, 42, session, "3")).isInstanceOf(
            ExternalDependencyUnavailableException.class
        );
        when(media.pending(9)).thenReturn(0);
        when(media.dead(9)).thenReturn(1);
        assertThatThrownBy(() -> state.current(9, 42, session, "3")).isInstanceOf(
            ExternalDependencyUnavailableException.class
        );
        when(media.dead(9)).thenReturn(0);
        core.member(9, 42).setLeaseUntil(now);
        assertThatThrownBy(() -> state.current(9, 42, session, "3")).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void staleBindingCannotRestorePublishingOrCrossSessionPermission() {
        var binding = fixture(true);
        binding.setPublishDesired(false);
        assertThatThrownBy(() -> state.current(9, 42, session, "3")).isInstanceOf(
            ExternalDependencyUnavailableException.class
        );
        binding.setPublishDesired(true);
        binding.setSessionId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> state.current(9, 42, session, "3")).isInstanceOf(
            ExternalDependencyUnavailableException.class
        );
        rooms.lockRoom(9).setStatus("CLOSING");
        assertThatThrownBy(() -> state.current(9, 42, session, "3")).isInstanceOf(ResourceNotFoundException.class);
    }
}
