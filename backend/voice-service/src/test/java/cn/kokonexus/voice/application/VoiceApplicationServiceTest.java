package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.identity.UserIdentity;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.media.VoiceMediaGateway;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VoiceApplicationServiceTest {

    /** SQL桩；真实SQL另在限权库验证。 */
    private final VoiceRoomMapper roomMapper = mock(VoiceRoomMapper.class);
    /** 媒体故障/重复调用夹具，不替代LiveKit。 */
    private final VoiceMediaGateway mediaGateway = mock(VoiceMediaGateway.class);
    /** 身份夹具，不访问生产账号。 */
    private final IdentityRpcService identityRpcService = mock(IdentityRpcService.class);
    /** 生产用例，读取事务代理另在MySQL验证。 */
    /** 短事务关闭代理的边界桩，真实SQL另验。 */
    private final VoiceClosureState closure = mock(VoiceClosureState.class);
    private final VoiceApplicationService service = new VoiceApplicationService(roomMapper, mediaGateway, closure);

    private VoiceRoom room(long id, long owner, String status) {
        var room = new VoiceRoom();
        room.setId(id);
        room.setOwnerId(owner);
        room.setStatus(status);
        room.setProviderRoomName("synthetic-voice-" + id);
        return room;
    }

    @Test
    void controlledRoomCannotBypassCoreWithLegacyPublishingCredential() {
        var room = room(9001, 7, "OPEN");
        room.setControlMode("CONTROLLED");
        when(roomMapper.selectById(9001L)).thenReturn(room);
        clearInvocations(identityRpcService);
        assertThatThrownBy(() -> service.join(7, 9001)).isInstanceOf(
            cn.kokonexus.common.api.ExternalDependencyUnavailableException.class
        );
        verifyNoInteractions(identityRpcService, mediaGateway);
    }

    @BeforeEach
    void injectRpcService() {
        ReflectionTestUtils.setField(service, "identityRpcService", identityRpcService);
        when(identityRpcService.findActiveUser("7")).thenReturn(
            new UserIdentity("7", "owner@example.com", "owner", "房主", null)
        );
        when(roomMapper.insert(any(VoiceRoom.class))).thenAnswer(invocation -> {
            VoiceRoom room = invocation.getArgument(0);
            room.setId(9001L);
            return 1;
        });
    }

    @Test
    void createProvisionsLiveKitBeforeOpeningRoom() {
        when(roomMapper.markOpen(9001L, "koko-voice-9001")).thenReturn(1);

        VoiceRoom room = service.create(7L, "Creator-Talk", "创作者夜谈", "声音与故事", 30);

        assertThat(room.getSlug()).isEqualTo("creator-talk");
        assertThat(room.getStatus()).isEqualTo("OPEN");
        assertThat(room.getProviderRoomName()).isEqualTo("koko-voice-9001");
        verify(mediaGateway).provision("koko-voice-9001", 30);
        verify(roomMapper).markOpen(9001L, "koko-voice-9001");
    }

    @Test
    void createMarksRecordFailedWhenLiveKitIsUnavailable() {
        org.mockito.Mockito.doThrow(new IllegalStateException("LiveKit 当前不可用"))
            .when(mediaGateway)
            .provision("koko-voice-9001", 30);

        assertThatThrownBy(() -> service.create(7L, "creator-talk", "创作者夜谈", null, 30))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("LiveKit 当前不可用");
        verify(roomMapper).markFailed(9001L);
    }

    @Test
    void ownerCursorIsBoundedAndStringPrecisionPreserved() {
        var first = room(Long.MAX_VALUE, 7, "OPEN");
        var second = room(Long.MAX_VALUE - 1, 7, "CLOSED");
        when(roomMapper.ownedRooms(7, null, 2)).thenReturn(java.util.List.of(first, second));
        var page = service.ownedRooms(7, null, 1);
        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextBefore()).isEqualTo(Long.toString(Long.MAX_VALUE));
        when(roomMapper.ownedRooms(7, Long.MAX_VALUE, 51)).thenReturn(java.util.List.of(second));
        assertThat(service.ownedRooms(7, Long.toString(Long.MAX_VALUE), 50).nextBefore()).isNull();
        verifyNoInteractions(mediaGateway);
    }

    @Test
    void malformedOwnerOrCursorDoesNotReachSql() {
        clearInvocations(roomMapper);
        for (String before : new String[] { "", "0", "-1", "+1", "01", "9223372036854775808", "1 OR 1=1" }) {
            assertThatThrownBy(() -> service.ownedRooms(7, before, 20)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int size : new int[] { 0, -1, 51, Integer.MAX_VALUE }) {
            assertThatThrownBy(() -> service.ownedRooms(7, null, size)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.ownedRooms(0, null, 20)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(roomMapper, mediaGateway);
    }

    @Test
    void closedOwnerRetryIsIdempotentButOtherOwnerCannotProbeOrDelete() {
        when(closure.begin(7, 9001)).thenReturn(room(9001, 7, "CLOSED"));
        doThrow(new cn.kokonexus.common.api.ResourceNotFoundException("无权操作")).when(closure).begin(8, 9001);
        service.close(7, 9001);
        assertThatThrownBy(() -> service.close(8, 9001)).isInstanceOf(
            cn.kokonexus.common.api.ResourceNotFoundException.class
        );
        verifyNoInteractions(mediaGateway);
        verify(roomMapper, never()).markClosed(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void compareAndSetRaceSucceedsOnlyWithConfirmedClosedOwnerFact() {
        when(closure.begin(7, 9001)).thenReturn(room(9001, 7, "CLOSING"));
        service.close(7, 9001);
        verify(mediaGateway).delete("synthetic-voice-9001");
        verify(closure).finish(7, 9001);
        when(closure.begin(7, 9002)).thenReturn(room(9002, 7, "CLOSING"));
        doThrow(new IllegalStateException("关闭未确认")).when(closure).finish(7, 9002);
        assertThatThrownBy(() -> service.close(7, 9002))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未确认");
    }

    @Test
    void failedMediaDeletionNeverMarksSqlClosed() {
        when(closure.begin(7, 9001)).thenReturn(room(9001, 7, "CLOSING"));
        doThrow(new IllegalStateException("synthetic-media-failure")).when(mediaGateway).delete("synthetic-voice-9001");
        assertThatThrownBy(() -> service.close(7, 9001)).isInstanceOf(IllegalStateException.class);
        verify(roomMapper, never()).markClosed(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void invalidOrFailedStateCannotDeleteMediaAndCredentialDoesNotLeakToString() {
        assertThatThrownBy(() -> service.close(0, 9001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.close(7, 0)).isInstanceOf(IllegalArgumentException.class);
        doThrow(new IllegalStateException("当前状态不能关闭")).when(closure).begin(7, 9001);
        assertThatThrownBy(() -> service.close(7, 9001)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mediaGateway);
        var credential = new VoiceApplicationService.JoinCredential(
            "ws://127.0.0.1",
            "synthetic-token-not-for-logs",
            "synthetic-room"
        );
        assertThat(credential.toString()).isEqualTo("JoinCredential[redacted]");
        assertThat(credential.token()).isEqualTo("synthetic-token-not-for-logs");
    }
}
