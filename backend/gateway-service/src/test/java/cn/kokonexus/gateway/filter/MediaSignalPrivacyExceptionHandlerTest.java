package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** 敏感媒体URI异常边界；普通路由仍交给现有处理器，不把所有错误伪装为成功。 */
class MediaSignalPrivacyExceptionHandlerTest {

    @Test
    void mediaFailureContainsNeitherTokenNorUnderlyingExceptionAndNonMediaErrorPropagates() throws Exception {
        var handler = new MediaSignalPrivacyExceptionHandler();
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/media/livekit/rtc?access_token=synthetic-token")
        );
        var failure = new IllegalStateException("synthetic-token private-upstream");
        assertThatThrownBy(() -> handler.handle(exchange, failure).block()).isSameAs(failure);
        // Mock响应没有native transport；使用真实环回Netty响应验证媒体URI的异常处理而不伪造运行环境。
        var pipeline = WebHttpHandlerBuilder.webHandler(current -> {
            current.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async()
                    .id("livekit-validate")
                    .uri("http://127.0.0.1:45181")
                    .predicate(ignored -> true)
                    .build()
            );
            return Mono.error(failure);
        })
            .exceptionHandler(handler)
            .build();
        var server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(new ReactorHttpHandlerAdapter(pipeline))
            .bindNow();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(
                HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" +
                            server.port() +
                            "/api/media/livekit/rtc/validate?access_token=synthetic-token"
                    )
                )
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body())
                .contains("MEDIA_SIGNAL_UNAVAILABLE")
                .doesNotContain("synthetic-token")
                .doesNotContain("private-upstream");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
        } finally {
            server.disposeNow();
        }
    }
}
