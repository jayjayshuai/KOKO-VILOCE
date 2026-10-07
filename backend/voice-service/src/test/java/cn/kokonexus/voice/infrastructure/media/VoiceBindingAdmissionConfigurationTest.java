package cn.kokonexus.voice.infrastructure.media;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 候选开关依赖检查，不把配置true等同RTC/部署验收。 */
class VoiceBindingAdmissionConfigurationTest {

    @Test
    void incompleteCandidateConfigurationCannotClaimBindingAdmission() {
        var env = new MockEnvironment();
        assertThatThrownBy(() -> new VoiceBindingAdmissionConfiguration(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("koko.voice.interaction-core-enabled", "true").withProperty(
            "koko.voice.media-plan-enabled",
            "true"
        );
        assertThatThrownBy(() -> new VoiceBindingAdmissionConfiguration(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("koko.voice.signal-admission-enabled", "true");
        assertThatCode(() -> new VoiceBindingAdmissionConfiguration(env)).doesNotThrowAnyException();
    }
}
