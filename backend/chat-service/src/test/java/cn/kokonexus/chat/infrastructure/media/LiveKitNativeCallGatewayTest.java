package cn.kokonexus.chat.infrastructure.media;

import static org.junit.jupiter.api.Assertions.*;

import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** 真实SDK/TCP/Twirp编码；供应商为隔离HTTP夹具，不冒充真实SFU音轨验收。 */
class LiveKitNativeCallGatewayTest {

    @Test
    void fixedPrivateRoomAndOriginalIdentityUseRealSdkAndOnlyStrictNotFoundIsIdempotent() throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>("{}");
        var count = new AtomicInteger();
        var path = new AtomicReference<String>();
        var payload = new AtomicReference<byte[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            count.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            payload.set(exchange.getRequestBody().readAllBytes());
            boolean protobufSuccess = status.get() == 200 && path.get().endsWith("/CreateRoom");
            byte[] bytes = protobufSuccess
                ? livekit.LivekitModels.Room.getDefaultInstance().toByteArray()
                : body.get().getBytes(StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .set("Content-Type", protobufSuccess ? "application/protobuf" : "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var media = new LiveKitNativeCallGateway(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "ws://127.0.0.1:7880/api/media/native-call/livekit",
                "synthetic-native-key",
                "synthetic-native-secret-".repeat(3)
            );
            String call = UUID.randomUUID().toString(),
                identity = UUID.randomUUID().toString();
            media.provision(call);
            assertEquals("/twirp/livekit.RoomService/CreateRoom", path.get());
            var create = livekit.LivekitRoom.CreateRoomRequest.parseFrom(payload.get());
            assertEquals(NativeCallTokens.room(call), create.getName());
            assertEquals(8, create.getMaxParticipants());
            media.remove(call, identity);
            assertEquals("/twirp/livekit.RoomService/RemoveParticipant", path.get());
            var remove = livekit.LivekitRoom.RoomParticipantIdentity.parseFrom(payload.get());
            assertEquals(NativeCallTokens.room(call), remove.getRoom());
            assertEquals(identity, remove.getIdentity());
            media.delete(call);
            assertEquals("/twirp/livekit.RoomService/DeleteRoom", path.get());
            status.set(404);
            body.set("{\"code\":\"not_found\"}");
            media.delete(call);
            media.remove(call, identity);
            for (String bad : new String[] {
                "<html>not found</html>",
                "{\"code\":\"not_found\",\"code\":\"not_found\"}",
                "{\"code\":true}",
                "x".repeat(1025),
            }) {
                body.set(bad);
                assertThrows(ExternalDependencyUnavailableException.class, () -> media.remove(call, identity));
            }
            status.set(503);
            body.set("{\"code\":\"unavailable\"}");
            int before = count.get();
            assertThrows(ExternalDependencyUnavailableException.class, () -> media.delete(call));
            assertEquals(before + 1, count.get());
            before = count.get();
            assertThrows(IllegalArgumentException.class, () -> media.delete("koko-voice-1"));
            assertEquals(before, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embeddedSecretsPublicPlaintextAndOtherMediaDomainsFailAtStartupWithoutLeakingConfiguration() throws Exception {
        String userInfoUrl = new java.net.URI(
            "wss",
            "synthetic-user:" + UUID.randomUUID(),
            "example.invalid",
            -1,
            "/api/media/native-call/livekit",
            null,
            null
        ).toString();
        for (String endpoint : new String[] {
            userInfoUrl,
            "ws://example.invalid/api/media/native-call/livekit",
            "wss://example.invalid/api/media/livekit",
            "wss://example.invalid/api/media/native-call/livekit?token=synthetic-secret",
        }) {
            var error = assertThrows(IllegalStateException.class, () ->
                new LiveKitNativeCallGateway(
                    "http://127.0.0.1:7880",
                    endpoint,
                    "synthetic-key",
                    "synthetic-secret-".repeat(3)
                )
            );
            assertFalse(error.getMessage().contains("synthetic-secret"));
            assertNull(error.getCause());
        }
    }
}
