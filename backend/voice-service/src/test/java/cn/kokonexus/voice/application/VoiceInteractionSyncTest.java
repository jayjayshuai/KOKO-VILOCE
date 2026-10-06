package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.voice.domain.VoiceInteraction.*;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 当前授权/条件投影/收据隔离的单元检查；不把Mapper桩视作实际MySQL或并发验收。 */
class VoiceInteractionSyncTest {

    /** 桩不调用任何远程数据。 */ private final VoiceInteractionMapper mapper = mock(VoiceInteractionMapper.class);
    /** 测试数据库时刻，不调整实际数据库时钟。 */ private final LocalDateTime now = LocalDateTime.of(2026, 10, 6, 3, 0);
    /** 高于JS安全整数的版本。 */ private final long revision = 9007199254741001L;
    /** 合成受控房间。 */ private VoiceRoom room;
    /** 被测用例，无外部媒体或身份调用。 */ private VoiceInteractionService service;

    @BeforeEach
    void fixture() {
        service = new VoiceInteractionService(mapper, true, mock(VoiceMediaPlanRecorder.class));
        room = new VoiceRoom();
        room.setId(1L);
        room.setOwnerId(42L);
        room.setControlMode("CONTROLLED");
        room.setStatus("OPEN");
        room.setInteractionVersion(revision);
        when(mapper.lockRoom(1)).thenReturn(room);
        when(mapper.databaseNow()).thenReturn(now);
        when(mapper.seats(1)).thenReturn(
            IntStream.rangeClosed(1, 8)
                .mapToObj(no -> {
                    var seat = new Seat();
                    seat.setSeatNo(no);
                    seat.setSeatState("EMPTY");
                    seat.setMuted(true);
                    return seat;
                })
                .toList()
        );
        when(mapper.activeMembers(1)).thenReturn(List.of());
        when(mapper.pendingRequests(1)).thenReturn(List.of());
        when(mapper.administrators(1)).thenReturn(List.of());
    }

    @Test
    void unchangedVersionStillChecksCurrentAuthorizationAndNeverReturnsPrivateSnapshot() {
        var reply = service.sync(42, 1, Long.toString(revision));
        assertThat(reply.version()).isEqualTo(Long.toString(revision));
        assertThat(reply.checkedAt()).isEqualTo(now);
        assertThat(reply.snapshot()).isNull();
        verify(mapper).member(1, 42);
        assertThatThrownBy(() -> service.sync(7, 1, Long.toString(revision))).isInstanceOf(
            ForbiddenOperationException.class
        );
        verify(mapper).member(1, 7);
        verify(mapper, never()).bumpVersion(anyLong());
    }

    @Test
    void firstReadAndChangedVersionReturnCurrentProjectionWithoutMediaOrForeignSession() {
        var first = service.sync(42, 1, null);
        assertThat(first.snapshot()).isNotNull();
        assertThat(first.snapshot().roomId()).isEqualTo("1");
        assertThat(first.snapshot().version()).isEqualTo(Long.toString(revision));
        assertThat(first.snapshot().mediaReady()).isFalse();
        assertThat(first.snapshot().mySessionId()).isNull();
        assertThat(first.snapshot().seats()).hasSize(8);
        assertThat(service.sync(42, 1, "9223372036854775807").snapshot()).isNotNull();
        assertThat(first.toString()).doesNotContain(Long.toString(revision));
    }

    @Test
    void expiredMemberIsNotAuthorizedEvenWhenCallerPresentsCurrentVersion() {
        var expired = new Member();
        expired.setUserId(7L);
        expired.setMemberState("ACTIVE");
        expired.setRoomRole("ADMIN");
        expired.setSessionId(UUID.randomUUID().toString());
        expired.setLeaseUntil(now);
        when(mapper.member(1, 7)).thenReturn(expired);
        assertThatThrownBy(() -> service.sync(7, 1, Long.toString(revision))).isInstanceOf(
            ForbiddenOperationException.class
        );
        room.setStatus("CLOSING");
        assertThatThrownBy(() -> service.sync(42, 1, Long.toString(revision))).isInstanceOf(
            ResourceNotFoundException.class
        );
    }

    @Test
    void expiryBeforeConditionalComparisonProducesNewSnapshotAndAudit() {
        var expired = new Member();
        expired.setUserId(7L);
        expired.setSessionId(UUID.randomUUID().toString());
        expired.setMemberState("ACTIVE");
        expired.setLeaseUntil(now.minusSeconds(1));
        when(mapper.activeMembers(1)).thenReturn(List.of(expired), List.of());
        when(mapper.setMemberState(1, 7, "LEFT")).thenReturn(1);
        when(mapper.bumpVersion(1)).thenReturn(1);
        when(
            mapper.audit(
                anyString(),
                eq(1L),
                isNull(),
                eq("EXPIRE"),
                eq(revision + 1),
                eq(now),
                isNull(),
                isNull(),
                isNull(),
                isNull()
            )
        ).thenReturn(1);
        var update = service.sync(42, 1, Long.toString(revision));
        assertThat(update.version()).isEqualTo(Long.toString(revision + 1));
        assertThat(update.snapshot()).isNotNull();
        assertThat(update.snapshot().members()).isEmpty();
    }

    @Test
    void ownReceiptCanBeCheckedAfterCloseButNeverQueriesOtherUserOrRevivesMembership() {
        room.setStatus("CLOSED");
        String id = UUID.randomUUID().toString();
        var receipt = new Receipt();
        receipt.setCommandType("JOIN");
        receipt.setResultVersion(revision);
        receipt.setResultSessionId(UUID.randomUUID().toString());
        when(mapper.receipt(1, 7, id)).thenReturn(receipt);
        var found = service.receipt(7, 1, id);
        assertThat(found.committed()).isTrue();
        assertThat(found.ack().version()).isEqualTo(Long.toString(revision));
        assertThat(found.toString()).doesNotContain(receipt.getResultSessionId());
        assertThat(service.receipt(42, 1, id).committed()).isFalse();
        assertThat(service.receipt(42, 1, id).ack()).isNull();
        verify(mapper, never()).member(anyLong(), anyLong());
        verify(mapper, never()).databaseNow();
        verify(mapper, never()).touchMember(anyLong(), anyLong(), anyString(), any(), any());
        verify(mapper, never()).bumpVersion(anyLong());
    }

    @Test
    void malformedSyncAndReceiptInputsAreRejectedBeforeSqlAndDisabledGateRemains() {
        clearInvocations(mapper);
        for (String invalid : new String[] { "", "-1", "01", "9223372036854775808" })
            assertThatThrownBy(() -> service.sync(42, 1, invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.receipt(42, 1, "1-1-1-1-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.receipt(0, 1, UUID.randomUUID().toString())).isInstanceOf(
            IllegalArgumentException.class
        );
        var closed = new VoiceInteractionService(mapper, false, mock(VoiceMediaPlanRecorder.class));
        assertThatThrownBy(() -> closed.sync(42, 1, "0")).isInstanceOf(
            cn.kokonexus.common.api.ExternalDependencyUnavailableException.class
        );
        assertThatThrownBy(() -> closed.receipt(42, 1, UUID.randomUUID().toString())).isInstanceOf(
            cn.kokonexus.common.api.ExternalDependencyUnavailableException.class
        );
        verifyNoInteractions(mapper);
    }
}
