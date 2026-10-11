package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 实际过滤器终止信号及输出脱敏，不用HTTP取消来冒充服务端成功。 */
@ExtendWith(OutputCaptureExtension.class)
class VoiceReadDiagnosticsFilterTest {

    @Test
    void failureAndCancellationAreDistinctWithoutLoggingPrivateDetails(CapturedOutput output) {
        var filter = new VoiceReadDiagnosticsFilter();
        var failed = MockServerWebExchange.from(
            MockServerHttpRequest.get(
                "/api/voice/rooms/123456789012/interaction/sync?knownVersion=synthetic-private-value"
            ).header("Cookie", "synthetic-private-cookie")
        );
        filter
            .filter(failed, next -> {
                next.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return Mono.empty();
            })
            .block();
        var cancelled = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/voice/rooms/123456789012/interaction/media-plan")
        );
        filter
            .filter(cancelled, next -> Mono.never())
            .subscribe()
            .dispose();
        var media = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/media/livekit/rtc/v1?access_token=synthetic-private-token")
        );
        filter
            .filter(media, next -> {
                next.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return Mono.empty();
            })
            .block();
        assertThat(output.getAll())
            .contains("endpoint=sync status=403", "endpoint=media-plan outcome=cancelled")
            .doesNotContain("123456789012", "synthetic-private", "access_token", "endpoint=rtc");
    }
}
