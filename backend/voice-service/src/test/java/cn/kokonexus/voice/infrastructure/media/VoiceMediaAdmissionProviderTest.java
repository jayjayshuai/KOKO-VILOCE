package cn.kokonexus.voice.infrastructure.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.voice.*;
import cn.kokonexus.voice.application.VoiceMediaAdmissionState;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import org.junit.jupiter.api.Test;

/** 用例顺序/失败关闭与当前房间状态单元检查；SQL锁/二跳网络需另行实证。 */
class VoiceMediaAdmissionProviderTest {

    @Test
    void disabledAndInvalidTokensNeverReachIdentityOrSql() {
        var verifier = mock(LiveKitJoinTokenVerifier.class);
        var identity = mock(MediaIdentityClient.class);
        var state = mock(VoiceMediaAdmissionState.class);
        assertThatThrownBy(() ->
            new VoiceMediaAdmissionProvider(verifier, identity, state, false).admit(
                new MediaAdmissionCommand("42", "synthetic-token")
            )
        ).isInstanceOf(MediaAdmissionUnavailableException.class);
        verifyNoInteractions(verifier, identity, state);
        assertThat(
            new VoiceMediaAdmissionProvider(verifier, identity, state, true).admit(
                new MediaAdmissionCommand("42", "synthetic-token")
            )
        ).isFalse();
        verifyNoInteractions(identity, state);
    }

    @Test
    void currentIdentityPrecedesSqlAndEveryDependencyFailureStaysUnavailableWithoutSecrets() {
        var verifier = mock(LiveKitJoinTokenVerifier.class);
        var identity = mock(MediaIdentityClient.class);
        var state = mock(VoiceMediaAdmissionState.class);
        var command = new MediaAdmissionCommand("42", "synthetic-token");
        var join = new LiveKitJoinTokenVerifier.VerifiedJoin(9, "koko-voice-9");
        when(verifier.verify(command.token(), "42")).thenReturn(join);
        when(identity.active(42)).thenReturn(true);
        when(state.allows(join)).thenReturn(true);
        var provider = new VoiceMediaAdmissionProvider(verifier, identity, state, true);
        assertThat(provider.admit(command)).isTrue();
        var order = inOrder(identity, state);
        order.verify(identity).active(42);
        order.verify(state).allows(join);
        when(state.allows(join)).thenThrow(new IllegalStateException("synthetic-secret database failure"));
        assertThatThrownBy(() -> provider.admit(command))
            .isInstanceOf(MediaAdmissionUnavailableException.class)
            .hasMessage("媒体准入暂不可用")
            .hasNoCause();
        reset(state);
        when(identity.active(42)).thenReturn(false);
        assertThat(provider.admit(command)).isFalse();
        verifyNoInteractions(state);
        assertThat(command.toString()).doesNotContain(command.token());
    }

    @Test
    void currentRoomStateBlocksClosingClosedControlledAndWrongProvider() {
        var mapper = mock(VoiceRoomMapper.class);
        var state = new VoiceMediaAdmissionState(mapper);
        var join = new LiveKitJoinTokenVerifier.VerifiedJoin(9, "koko-voice-9");
        var room = new VoiceRoom();
        room.setStatus("OPEN");
        room.setControlMode("LEGACY");
        room.setProviderRoomName("koko-voice-9");
        when(mapper.lockRoom(9)).thenReturn(room);
        assertThat(state.allows(join)).isTrue();
        for (String status : java.util.List.of("CLOSING", "CLOSED", "FAILED", "PROVISIONING")) {
            room.setStatus(status);
            assertThat(state.allows(join)).isFalse();
        }
        room.setStatus("OPEN");
        room.setControlMode("CONTROLLED");
        assertThat(state.allows(join)).isFalse();
        room.setControlMode("LEGACY");
        room.setProviderRoomName("different-room");
        assertThat(state.allows(join)).isFalse();
        when(mapper.lockRoom(9)).thenReturn(null);
        assertThat(state.allows(join)).isFalse();
    }

    @Test
    void identityClientDistinguishesInactiveFromNetworkOrCorruptReplyWithoutPrintingToken() {
        var rpc = mock(cn.kokonexus.api.identity.IdentityRpcService.class);
        var client = new MediaIdentityClient();
        org.springframework.test.util.ReflectionTestUtils.setField(client, "identity", rpc);
        when(rpc.findActiveUser("42")).thenThrow(new IllegalArgumentException("synthetic-disabled"));
        assertThat(client.active(42)).isFalse();
        doThrow(new org.apache.dubbo.rpc.RpcException("synthetic-private-network")).when(rpc).findActiveUser("42");
        assertThatThrownBy(() -> client.active(42))
            .isInstanceOf(MediaAdmissionUnavailableException.class)
            .hasNoCause();
        doReturn(null).when(rpc).findActiveUser("42");
        assertThat(client.active(42)).isFalse();
        assertThat(client.active(0)).isFalse();
    }
}
