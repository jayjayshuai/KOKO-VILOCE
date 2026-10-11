package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.common.api.*;
import cn.kokonexus.voice.infrastructure.media.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RPC在SQL之前，只有当前数据库投影可进入签发，禁止旧接口回退。 */
class VoiceMediaCredentialIssuerTest {

    @Test
    void committedWebsiteRotationDoesNotSignWhileRetirementIsPreparing() {
        var identity = mock(MediaIdentityClient.class);
        var state = mock(VoiceMediaCredentialState.class);
        var media = mock(VoiceMediaGateway.class);
        when(identity.active(42)).thenReturn(true);
        when(state.current(9, 42, "synthetic-session", "3", SCOPE)).thenReturn(
            new VoiceMediaCredentialState.Grant(
                "koko-voice-9",
                UUID.randomUUID().toString(),
                "合成成员",
                UUID.randomUUID().toString(),
                "2",
                1,
                true,
                false
            )
        );
        assertThatThrownBy(() ->
            new VoiceMediaCredentialIssuer(identity, state, media, true).issue(42, 9, "synthetic-session", "3", SCOPE)
        ).isInstanceOf(ExternalDependencyUnavailableException.class);
        verifyNoInteractions(media);
    }

    /** 合成高熵网站会话摘要，不是生产Cookie。 */ private static final String SCOPE =
        cn.kokonexus.api.voice.WebsiteSessionScope.fromToken("synthetic-website-token");

    @Test
    void sqlFailureNeverSignsAndNeverPublishesPrivateDriverMessage() {
        var identity = mock(MediaIdentityClient.class);
        var state = mock(VoiceMediaCredentialState.class);
        var media = mock(VoiceMediaGateway.class);
        when(identity.active(42)).thenReturn(true);
        when(state.current(9, 42, "synthetic-session", "3", SCOPE)).thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("private-driver-diagnostic")
        );
        assertThatThrownBy(() ->
            new VoiceMediaCredentialIssuer(identity, state, media, true).issue(42, 9, "synthetic-session", "3", SCOPE)
        )
            .isInstanceOf(ExternalDependencyUnavailableException.class)
            .hasMessage("当前媒体授权读取暂不可用")
            .hasNoCause();
        verifyNoInteractions(media);
    }

    @Test
    void disabledOrInactiveDoesNotReadBindingOrSignToken() {
        var identity = mock(MediaIdentityClient.class);
        var state = mock(VoiceMediaCredentialState.class);
        var media = mock(VoiceMediaGateway.class);
        assertThatThrownBy(() ->
            new VoiceMediaCredentialIssuer(identity, state, media, false).issue(42, 9, "x", "0", SCOPE)
        ).isInstanceOf(ExternalDependencyUnavailableException.class);
        verifyNoInteractions(identity, state, media);
        assertThatThrownBy(() ->
            new VoiceMediaCredentialIssuer(identity, state, media, true).issue(42, 9, "x", "0", SCOPE)
        ).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(state, media);
    }

    @Test
    void signsOnlyCurrentGrantAndKeepsCredentialOutOfToString() {
        var identity = mock(MediaIdentityClient.class);
        var state = mock(VoiceMediaCredentialState.class);
        var media = mock(VoiceMediaGateway.class);
        String session = UUID.randomUUID().toString(),
            opaque = UUID.randomUUID().toString();
        when(identity.active(42)).thenReturn(true);
        when(state.current(9, 42, session, "4", SCOPE)).thenReturn(
            new VoiceMediaCredentialState.Grant(
                "koko-voice-9",
                opaque,
                "合成成员",
                session,
                "9007199254741001",
                null,
                false
            )
        );
        when(media.issueBoundJoinToken("koko-voice-9", opaque, "合成成员", false)).thenReturn("synthetic-token");
        when(media.publicUrl()).thenReturn("wss://app.example.invalid/api/media/livekit");
        var result = new VoiceMediaCredentialIssuer(identity, state, media, true).issue(42, 9, session, "4", SCOPE);
        assertThat(result.canPublish()).isFalse();
        assertThat(result.expiresInSeconds()).isEqualTo(120);
        assertThat(result.toString()).doesNotContain("synthetic-token", opaque, session);
        var ordered = inOrder(identity, state, media);
        ordered.verify(identity).active(42);
        ordered.verify(state).current(9, 42, session, "4", SCOPE);
        ordered.verify(media).issueBoundJoinToken("koko-voice-9", opaque, "合成成员", false);
        verify(media, never()).issueJoinToken(anyString(), anyLong(), anyString());
    }
}
