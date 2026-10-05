package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.identity.UserIdentity;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.media.VoiceMediaGateway;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VoiceApplicationServiceTest {

    private final VoiceRoomMapper roomMapper = mock(VoiceRoomMapper.class);
    private final VoiceMediaGateway mediaGateway = mock(VoiceMediaGateway.class);
    private final IdentityRpcService identityRpcService = mock(IdentityRpcService.class);
    private final VoiceApplicationService service = new VoiceApplicationService(roomMapper, mediaGateway);

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
}
