package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.voice.domain.VoiceInteraction.*;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 权限分支单测；真实事务/SFU禁麦在独立测试房间另行验收。 */
class VoiceSpeakingPermissionTest {

    @Test
    void memberAndAdministratorCannotUnlockTheirOwnRoomMute() {
        for (String role : List.of("LISTENER", "ADMIN")) {
            var mapper = mock(VoiceInteractionMapper.class);
            var service = fixture(mapper, role);
            String session = mapper.member(1, 8).getSessionId();
            assertThatThrownBy(() ->
                service.command(
                    8,
                    1,
                    UUID.randomUUID().toString(),
                    session,
                    "0",
                    CommandType.MUTE,
                    1,
                    null,
                    null,
                    false
                )
            ).isInstanceOf(ForbiddenOperationException.class);
            verify(mapper, never()).saveSeat(any());
            verify(mapper, never()).insertReceipt(anyLong(), anyLong(), anyString(), any(), any());
        }
    }

    @Test
    void ownerCanForbidAndPermitMemberButMemberCanOnlyCloseOwnRoomMute() {
        var mapper = mock(VoiceInteractionMapper.class);
        var service = fixture(mapper, "LISTENER");
        String owner = mapper.member(1, 7).getSessionId();
        service.command(7, 1, UUID.randomUUID().toString(), owner, "0", CommandType.MUTE, 1, null, null, true);
        assertThat(mapper.seats(1).getFirst().getMuted()).isTrue();
        service.command(7, 1, UUID.randomUUID().toString(), owner, "1", CommandType.MUTE, 1, null, null, false);
        assertThat(mapper.seats(1).getFirst().getMuted()).isFalse();
        service.command(
            8,
            1,
            UUID.randomUUID().toString(),
            mapper.member(1, 8).getSessionId(),
            "2",
            CommandType.MUTE,
            1,
            null,
            null,
            true
        );
        assertThat(mapper.seats(1).getFirst().getMuted()).isTrue();
        verify(mapper, times(3)).saveSeat(any());
    }

    private VoiceInteractionService fixture(VoiceInteractionMapper mapper, String role) {
        var now = LocalDateTime.of(2026, 10, 11, 12, 0);
        var room = new VoiceRoom();
        room.setId(1L);
        room.setOwnerId(7L);
        room.setStatus("OPEN");
        room.setControlMode("CONTROLLED");
        room.setInteractionVersion(0L);
        var owner = member(7, "OWNER", now);
        var target = member(8, role, now);
        var seats = new ArrayList<Seat>();
        for (int no = 1; no <= 8; no++) {
            var seat = new Seat();
            seat.setRoomId(1L);
            seat.setSeatNo(no);
            seat.setSeatState(no == 1 ? "ON_MIC" : "EMPTY");
            seat.setMuted(true);
            if (no == 1) {
                seat.setUserId(8L);
                seat.setSessionId(target.getSessionId());
            }
            seats.add(seat);
        }
        when(mapper.lockRoom(1)).thenReturn(room);
        when(mapper.databaseNow()).thenReturn(now);
        when(mapper.member(1, 7)).thenReturn(owner);
        when(mapper.member(1, 8)).thenReturn(target);
        when(mapper.activeMembers(1)).thenReturn(List.of(owner, target));
        when(mapper.seats(1)).thenReturn(seats);
        when(mapper.saveSeat(any())).thenReturn(1);
        when(mapper.bumpVersion(1)).thenReturn(1);
        when(
            mapper.audit(anyString(), anyLong(), any(), anyString(), anyLong(), any(), any(), any(), any(), any())
        ).thenReturn(1);
        when(mapper.insertReceipt(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(1);
        return new VoiceInteractionService(mapper, true, mock(VoiceMediaPlanRecorder.class));
    }

    private Member member(long user, String role, LocalDateTime now) {
        var member = new Member();
        member.setRoomId(1L);
        member.setUserId(user);
        member.setRoomRole(role);
        member.setMemberState("ACTIVE");
        member.setSessionId(UUID.randomUUID().toString());
        member.setLeaseUntil(now.plusSeconds(90));
        return member;
    }
}
