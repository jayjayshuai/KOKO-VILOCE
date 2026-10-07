package cn.kokonexus.voice.infrastructure.media;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 默认候选不可单独开启；配置检查不是公开SFU或实际音轨认证。 */
class VoiceMediaCredentialConfigurationTest {

    @Test
    void dependencyAndGatewayEndpointAreRequiredEvenForCandidate() {
        var env = new MockEnvironment();
        assertThatThrownBy(() -> new VoiceMediaCredentialConfiguration(env)).isInstanceOf(IllegalStateException.class);
        for (String key : java.util.List.of(
            "koko.voice.interaction-core-enabled",
            "koko.voice.media-plan-enabled",
            "koko.voice.binding-admission-enabled",
            "koko.voice.signal-admission-enabled",
            "koko.voice.media-retirement.enabled"
        ))
            env.withProperty(key, "true");
        env.withProperty("livekit.public-url", "wss://app.example.invalid/koko-api/media/livekit");
        assertThatCode(() -> new VoiceMediaCredentialConfiguration(env)).doesNotThrowAnyException();
        env.withProperty("livekit.public-url", "ws://127.0.0.1:45173/api/media/livekit");
        assertThatCode(() -> new VoiceMediaCredentialConfiguration(env)).doesNotThrowAnyException();
        for (String url : java.util.List.of(
            "ws://remote.example.invalid/api/media/livekit",
            "wss://rtc.example.invalid/rtc",
            "wss://app.example.invalid/api/media/livekit?token=x",
            "wss://app.example.invalid/api/media/livekit#fragment"
        )) {
            env.withProperty("livekit.public-url", url);
            assertThatThrownBy(() -> new VoiceMediaCredentialConfiguration(env)).isInstanceOf(
                IllegalStateException.class
            );
        }
    }
}
