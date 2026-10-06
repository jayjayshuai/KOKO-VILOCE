package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.voice.domain.VoiceMediaPlan.Retirement;
import cn.kokonexus.voice.infrastructure.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 格式/预算/候选关闭单元检查；CAS/并发/事务另由固定本机MySQL实证。 */
class VoiceMediaRetirementStateTest {

    @Test
    void disabledPlanDoesNotQueryAnySqlOrFakeReady() {
        var core = mock(VoiceInteractionMapper.class);
        var mapper = mock(VoiceMediaPlanMapper.class);
        var recorder = new VoiceMediaPlanRecorder(core, mapper, false);
        var room = new cn.kokonexus.voice.domain.VoiceRoom();
        room.setControlMode("CONTROLLED");
        recorder.reconcile(room);
        var view = recorder.view(1, 7);
        assertThat(view.tracked()).isFalse();
        assertThat(view.mediaReady()).isFalse();
        assertThat(view.pendingRetirements()).isNull();
        verifyNoInteractions(core, mapper);
    }

    @Test
    void invalidLeaseAndAttemptCountNeverReachMapper() {
        var mapper = mock(VoiceMediaPlanMapper.class);
        var state = new VoiceMediaRetirementState(mapper);
        assertThatThrownBy(() -> state.claim("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        var job = new Retirement();
        job.setId(UUID.randomUUID().toString());
        job.setLeaseToken(UUID.randomUUID().toString());
        job.setAttempts(11);
        assertThatThrownBy(() -> state.failed(job)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void claimBudgetIsConsumedBeforeExternalCallAndFailureDelayIsBounded() {
        var mapper = mock(VoiceMediaPlanMapper.class);
        var state = new VoiceMediaRetirementState(mapper);
        var job = new Retirement();
        job.setId(UUID.randomUUID().toString());
        job.setAttempts(9);
        when(mapper.dueExpired(4)).thenReturn(List.of(job));
        when(mapper.duePending(3)).thenReturn(List.of());
        String token = UUID.randomUUID().toString();
        when(mapper.claim(job.getId(), token)).thenReturn(1);
        assertThat(state.claim(token).getFirst().getAttempts()).isEqualTo(10);
        when(mapper.failed(job.getId(), token, true, 300)).thenReturn(1);
        assertThat(state.failed(job)).isTrue();
        verify(mapper, never()).exhaustExpired();
    }
}
