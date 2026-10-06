package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import org.junit.jupiter.api.Test;

/** 锁后归属/意图边界，真实竞争与事务另用MySQL。 */
class VoiceClosureStateTest {

    /** 当前读边界桩。 */ private final VoiceRoomMapper rooms = mock(VoiceRoomMapper.class);
    /** 受控审计边界桩。 */ private final VoiceInteractionMapper core = mock(VoiceInteractionMapper.class);
    /** 实际短事务逻辑，媒体计划为明确桩，非代理验证。 */ private final VoiceClosureState state = new VoiceClosureState(
        rooms,
        core,
        mock(VoiceMediaPlanRecorder.class)
    );

    private VoiceRoom room(String status) {
        var room = new VoiceRoom();
        room.setId(1L);
        room.setOwnerId(7L);
        room.setStatus(status);
        return room;
    }

    @Test
    void oldOwnerCannotClaimClosingIntentAfterTransfer() {
        when(rooms.lockRoom(1)).thenReturn(room("OPEN"));
        assertThatThrownBy(() -> state.begin(8, 1)).isInstanceOf(
            cn.kokonexus.common.api.ResourceNotFoundException.class
        );
        verify(rooms, never()).markClosing(anyLong(), anyLong());
        verifyNoInteractions(core);
    }

    @Test
    void onlyConfirmedIntentCanFinishAndClosedRetryDoesNotWrite() {
        when(rooms.lockRoom(1)).thenReturn(room("OPEN"));
        assertThatThrownBy(() -> state.finish(7, 1)).isInstanceOf(IllegalStateException.class);
        when(rooms.lockRoom(1)).thenReturn(room("CLOSED"));
        state.finish(7, 1);
        verify(rooms, never()).markClosed(anyLong(), anyLong());
    }

    @Test
    void repeatedClosingIntentDoesNotReclaimOrReaudit() {
        when(rooms.lockRoom(1)).thenReturn(room("CLOSING"));
        assertThat(state.begin(7, 1).getStatus()).isEqualTo("CLOSING");
        verify(rooms, never()).markClosing(anyLong(), anyLong());
        verifyNoInteractions(core);
    }
}
