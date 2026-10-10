package cn.kokonexus.voice.infrastructure.media;

import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 新凭据必须经过Gateway绑定准入及退场流程；开关不是生产或RTP就绪证明。 */
@Configuration
@ConditionalOnProperty(prefix = "koko.voice", name = "media-credentials-enabled", havingValue = "true")
public class VoiceMediaCredentialConfiguration {

    public VoiceMediaCredentialConfiguration(Environment environment) {
        for (String key : List.of(
            "koko.voice.interaction-core-enabled",
            "koko.voice.media-plan-enabled",
            "koko.voice.binding-admission-enabled",
            "koko.voice.signal-admission-enabled",
            "koko.voice.media-retirement.enabled",
            "koko.voice.session-reaper.enabled"
        ))
            if (!environment.getProperty(key, Boolean.class, false)) throw new IllegalStateException(
                "受控凭据需先配置完整成员、绑定准入及退场依赖"
            );
        var url = URI.create(environment.getRequiredProperty("livekit.public-url"));
        boolean secure = "wss".equals(url.getScheme());
        boolean local =
            "ws".equals(url.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(url.getHost());
        if (
            (!secure && !local) ||
            url.getHost() == null ||
            url.getUserInfo() != null ||
            url.getQuery() != null ||
            url.getFragment() != null ||
            !List.of("/api/media/livekit", "/koko-api/media/livekit").contains(url.getPath())
        ) throw new IllegalStateException("受控凭据必须使用Gateway信令基址及安全传输，环回开发除外");
    }
}
