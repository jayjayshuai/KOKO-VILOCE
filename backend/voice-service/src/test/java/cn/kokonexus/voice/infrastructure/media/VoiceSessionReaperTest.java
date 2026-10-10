package cn.kokonexus.voice.infrastructure.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.voice.application.VoiceInteractionService;
import cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 扫描编排和启动保护；数据库行锁/索引/退场与真实RTC另外验证。 */
class VoiceSessionReaperTest {

    @Test
    void incompleteDependenciesAndUnboundedSettingsRefuseStartup() {
        var mapper = mock(VoiceMediaPlanMapper.class);
        var core = mock(VoiceInteractionService.class);
        assertThatThrownBy(() -> new VoiceSessionReaper(mapper, core, false, true, true, 4, 5000)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> new VoiceSessionReaper(mapper, core, true, false, true, 4, 5000)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> new VoiceSessionReaper(mapper, core, true, true, false, 4, 5000)).isInstanceOf(
            IllegalArgumentException.class
        );
        for (int batch : new int[] { 0, 17 })
            assertThatThrownBy(() -> new VoiceSessionReaper(mapper, core, true, true, true, batch, 5000)).isInstanceOf(
                IllegalArgumentException.class
            );
        for (long interval : new long[] { 999, 60001 })
            assertThatThrownBy(() -> new VoiceSessionReaper(mapper, core, true, true, true, 4, interval)).isInstanceOf(
                IllegalArgumentException.class
            );
        verifyNoInteractions(mapper, core);
    }

    @Test
    void failingRoomDoesNotStarveLaterRoomsAndEmptyPageWrapsCursor() {
        var mapper = mock(VoiceMediaPlanMapper.class);
        var core = mock(VoiceInteractionService.class);
        var worker = new VoiceSessionReaper(mapper, core, true, true, true, 4, 5000);
        when(mapper.expiredMemberRooms(0, 4)).thenReturn(List.of(10L, 20L));
        when(mapper.expiredMemberRooms(20, 4)).thenReturn(List.of());
        doThrow(new IllegalStateException("synthetic SQL failure")).when(core).reapExpiredMembers(10);
        worker.tick();
        worker.tick();
        worker.tick();
        verify(core, times(2)).reapExpiredMembers(10);
        verify(core, times(2)).reapExpiredMembers(20);
        verify(mapper, times(2)).expiredMemberRooms(0, 4);
    }

    @Test
    void discoveryFailureDoesNotAdvanceAndUnexpectedPagesCannotWrite() {
        var mapper = mock(VoiceMediaPlanMapper.class);
        var core = mock(VoiceInteractionService.class);
        var worker = new VoiceSessionReaper(mapper, core, true, true, true, 1, 5000);
        when(mapper.expiredMemberRooms(0, 1))
            .thenThrow(new IllegalStateException("synthetic discovery failure"))
            .thenReturn(List.of(10L, 20L))
            .thenReturn(List.of(30L));
        worker.tick();
        worker.tick();
        verifyNoInteractions(core);
        worker.tick();
        verify(core).reapExpiredMembers(30);
    }

    @Test
    void reentrantTickDoesNotCreateSecondDatabaseScan() {
        var mapper = mock(VoiceMediaPlanMapper.class);
        var core = mock(VoiceInteractionService.class);
        var worker = new VoiceSessionReaper(mapper, core, true, true, true, 4, 5000);
        when(mapper.expiredMemberRooms(0, 4)).thenAnswer(ignored -> {
            worker.tick();
            return List.of();
        });
        worker.tick();
        verify(mapper).expiredMemberRooms(0, 4);
        verifyNoInteractions(core);
    }
}
