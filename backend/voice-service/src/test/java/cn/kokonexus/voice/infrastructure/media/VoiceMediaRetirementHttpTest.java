package cn.kokonexus.voice.infrastructure.media;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** 实际SDK/Twirp HTTP请求与有界错误语义；供应商响应为明确夹具，不宣称真实SFU退场。 */
class VoiceMediaRetirementHttpTest {

    @Test
    void realSdkUsesFixedTargetAndOnlyTwirpNotFoundIsIdempotent() throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>("{}");
        var calls = new AtomicInteger();
        var requestPath = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var payload = new AtomicReference<byte[]>();
        var contentType = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new LiveKitVoiceMediaGateway(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "ws://127.0.0.1:7880",
                "isolated-retirement-api-key",
                "synthetic-retirement-signature-key-private"
            );
            String identity = UUID.randomUUID().toString();
            client.removeParticipant("koko-voice-9", identity);
            assertThat(requestPath.get()).isEqualTo("/twirp/livekit.RoomService/RemoveParticipant");
            assertThat(auth.get()).startsWith("Bearer ");
            if (contentType.get().contains("json")) {
                var request = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload.get());
                assertThat(request.path("room").asText()).isEqualTo("koko-voice-9");
                assertThat(request.path("identity").asText()).isEqualTo(identity);
            } else {
                var request = livekit.LivekitRoom.RoomParticipantIdentity.parseFrom(payload.get());
                assertThat(request.getRoom()).isEqualTo("koko-voice-9");
                assertThat(request.getIdentity()).isEqualTo(identity);
            }
            status.set(404);
            body.set("{\"code\":\"not_found\",\"msg\":\"synthetic participant absent\"}");
            client.removeParticipant("koko-voice-9", identity);
            for (String error : java.util.List.of(
                "<html>proxy404</html>",
                "{\"code\":\"bad_route\"}",
                "{\"code\":\"not_found\",\"code\":\"not_found\"}",
                " ",
                "x".repeat(1025)
            )) {
                body.set(error);
                assertThatThrownBy(() -> client.removeParticipant("koko-voice-9", identity)).isInstanceOf(
                    IllegalStateException.class
                );
            }
            int before = calls.get();
            status.set(503);
            body.set("{\"code\":\"unavailable\"}");
            assertThatThrownBy(() -> client.removeParticipant("koko-voice-9", identity)).isInstanceOf(
                IllegalStateException.class
            );
            assertThat(calls.get()).isEqualTo(before + 1);
            before = calls.get();
            assertThatThrownBy(() -> client.removeParticipant("koko-voice-9223372036854775808", identity)).isInstanceOf(
                IllegalArgumentException.class
            );
            assertThat(calls.get()).isEqualTo(before);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void actualHttpCallTimeoutBoundsAnUnresponsiveSupplierWithoutHiddenRetry() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new LiveKitVoiceMediaGateway(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "ws://127.0.0.1:7880",
                "isolated-retirement-timeout-key",
                "synthetic-retirement-timeout-signature-key"
            );
            long started = System.nanoTime();
            assertThatThrownBy(() ->
                client.removeParticipant("koko-voice-9", UUID.randomUUID().toString())
            ).isInstanceOf(IllegalStateException.class);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(entered.getCount()).isZero();
            assertThat(elapsed).isLessThan(6500);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
