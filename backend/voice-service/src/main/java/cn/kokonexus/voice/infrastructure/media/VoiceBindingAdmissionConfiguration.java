package cn.kokonexus.voice.infrastructure.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 轮次准入不得绕过成员/计划/信令边界单独配置成可用；这是候选配置检查，不是部署认证。 */
@Configuration
@ConditionalOnProperty(prefix = "koko.voice", name = "binding-admission-enabled", havingValue = "true")
public class VoiceBindingAdmissionConfiguration {

    public VoiceBindingAdmissionConfiguration(Environment environment) {
        for (String key : java.util.List.of(
            "koko.voice.interaction-core-enabled",
            "koko.voice.media-plan-enabled",
            "koko.voice.signal-admission-enabled"
        ))
            if (!environment.getProperty(key, Boolean.class, false)) throw new IllegalStateException(
                "轮次准入须先配置核心、计划及信令准入"
            );
    }
}
