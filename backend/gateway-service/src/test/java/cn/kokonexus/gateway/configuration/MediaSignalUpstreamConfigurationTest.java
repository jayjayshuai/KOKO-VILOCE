package cn.kokonexus.gateway.configuration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 防止旧服务器Compose遗漏上游变量后误连容器localhost；不以配置测试证明SFU可达。 */
class MediaSignalUpstreamConfigurationTest {

    @Test
    void actualSpringConfigurationRefusesEnabledCandidateWithMissingEnvironment() {
        new ApplicationContextRunner()
            .withUserConfiguration(MediaSignalUpstreamConfiguration.class)
            .withPropertyValues("koko.gateway.media-admission.enabled=true")
            .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
            .withUserConfiguration(MediaSignalUpstreamConfiguration.class)
            .withPropertyValues(
                "koko.gateway.media-admission.enabled=true",
                "LIVEKIT_SIGNAL_WS_URI=ws://livekit:7880",
                "LIVEKIT_SIGNAL_HTTP_URI=http://livekit:7880"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void closedCandidateDoesNotRequireOrParseUnusedUpstream() {
        assertThatCode(() -> new MediaSignalUpstreamConfiguration(false, "", "bad uri")).doesNotThrowAnyException();
    }

    @Test
    void requiresBothExplicitEndpointsWithoutLeakingInvalidValue() {
        for (String invalid : new String[] {
            "",
            "bad uri",
            "ws://user:secret@localhost:7880",
            "ws://localhost:7880/rtc",
            "ws://localhost:7880?token=synthetic-token",
            "ws://localhost:0",
            "ws://localhost:65536",
        }) {
            assertThatThrownBy(() -> new MediaSignalUpstreamConfiguration(true, invalid, "http://localhost:7880"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("user:secret")
                .hasMessageNotContaining("synthetic-token")
                .hasNoCause();
        }
        assertThatThrownBy(() -> new MediaSignalUpstreamConfiguration(true, "ws://localhost:7880", "")).isInstanceOf(
            IllegalStateException.class
        );
    }

    @Test
    void endpointsMustReferToSameHostPortAndSecurityMode() {
        assertThatCode(() ->
            new MediaSignalUpstreamConfiguration(true, "ws://livekit:7880", "http://livekit:7880/")
        ).doesNotThrowAnyException();
        assertThatCode(() ->
            new MediaSignalUpstreamConfiguration(true, "wss://media.invalid", "https://media.invalid:443")
        ).doesNotThrowAnyException();
        for (String mismatch : new String[] { "http://other:7880", "http://livekit:7881", "https://livekit:7880" }) {
            assertThatThrownBy(() ->
                new MediaSignalUpstreamConfiguration(true, "ws://livekit:7880", mismatch)
            ).isInstanceOf(IllegalStateException.class);
        }
    }
}
