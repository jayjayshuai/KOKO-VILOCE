package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.voice.MediaAdmissionCommand;
import cn.kokonexus.gateway.infrastructure.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/** 在真实调度线程计时，防止首轮延迟与重复延迟相加造成核验周期翻倍。 */
class MediaWebSocketLeaseTimingTest {

    @Test
    void eachRepeatWaitsOneConfiguredInterval() throws Exception {
        var checked = new CountDownLatch(4);
        var media = mock(MediaAdmissionClient.class);
        when(media.retain(any())).thenAnswer(call -> {
            checked.countDown();
            return true;
        });
        var website = mock(MediaWebsiteSessionCheck.class);
        when(website.active("synthetic-cookie", "42")).thenReturn(true);
        try (var lease = new MediaWebSocketLease(media, website, 500, 1, 1, 4)) {
            var proof = new MediaConnectionProof(
                new MediaAdmissionCommand("42", "synthetic-token"),
                "synthetic-cookie"
            );
            var monitoring = lease.handle(mock(WebSocketSession.class), session -> Mono.never(), proof).subscribe();
            try {
                // 单延迟四轮约2秒；重复叠加延迟至少3.5秒，保留1秒调度余量。
                assertThat(checked.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                monitoring.dispose();
            }
        }
    }
}
