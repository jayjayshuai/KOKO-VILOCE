package cn.kokonexus.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 隔离完整 Boot/Gateway/Nacos/Redis 检查；真实注册账号，凭据不落日志，不接受任意目标。 */
public final class ChatGatewayNetworkCheck {

    /** 合成响应只在内存检查；绝不打印含令牌的登录响应。 */
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /** 仅连接固定环回测试端口，无公网凭据传输。 */
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** 与夹具 Gateway 配置一致的浏览器 Origin。 */
    private static final String ORIGIN = "http://127.0.0.1:42190";
    /** 所有已创建网络句柄，finally 只关闭本轮对象。 */
    private static final List<Wire> WIRES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String jdbc = System.getenv("CHAT_GATEWAY_JDBC");
        check(
            jdbc != null && jdbc.startsWith("jdbc:mysql://127.0.0.1:33068/koko_chat_gateway_chat_20261005?"),
            "Fixed isolated JDBC required"
        );
        check("koko_chat_gateway_20261005".equals(System.getenv("CHAT_GATEWAY_SQL_USER")), "Limited user required");
        try {
            discovery(2);
            check(
                sql("SELECT COUNT(*) FROM chat_flyway_schema_history WHERE success=1") == 4,
                "Chat V1-V4 migration missing"
            );
            pass("BOOT_1", "actual discovery has two HTTP instances with distinct WS ports; chat Flyway V1-V4");
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User owner = register("cg_a_" + suffix),
                peer = register("cg_b_" + suffix),
                outsider = register("cg_c_" + suffix);
            var group = api(
                "POST",
                "/api/chat/groups",
                owner,
                Map.of("title", "Gateway network fixture", "handles", List.of(peer.handle)),
                201
            );
            String groupId = group.path("id").asText();
            check(group.path("ownerId").asText().equals(owner.id), "Forged header replaced trusted identity");
            api("GET", "/api/chat/conversations/" + groupId + "/messages", outsider, null, 404);
            api("GET", "/api/chat/conversations", null, null, 401);
            raw("GET", "http://127.0.0.1:42197/api/chat/conversations", null, null, null, 403);
            raw("GET", "http://127.0.0.1:42198/api/chat/conversations", null, null, null, 403);
            pass(
                "HTTP_2",
                "real registration/session/identity RPC; forged headers cannot impersonate; anonymous and direct bypass rejected"
            );

            Wire sender = gateway(owner),
                receiver = gateway(peer);
            for (int attempt = 0; attempt < 6 && !split(); attempt++) {
                receiver.close();
                receiver = gateway(peer);
            }
            check(split(), "Gateway WS did not reach separate nodes");
            sender.drain();
            receiver.drain();
            sender.send(groupId, "synthetic cross-node via Gateway");
            check(
                sender.await("ACK").path("message").path("senderId").asText().equals(owner.id),
                "Forged WS identity accepted"
            );
            JsonNode hint = receiver.await("SYNC");
            check(hint.size() == 1, "SYNC contained private data");
            check(
                api("GET", "/api/chat/conversations/" + groupId + "/messages?after=0", peer, null, 200).size() == 1,
                "Committed history missing"
            );
            pass(
                "WS_3",
                "same Gateway selects different Netty nodes, committed ACK and targeted body-free SYNC, real HTTP fact reload"
            );

            Wire outsideWire = gateway(outsider);
            outsideWire.drain();
            api(
                "POST",
                "/api/chat/conversations/" + groupId + "/members",
                owner,
                Map.of("handle", outsider.handle),
                204
            );
            outsideWire.await("SYNC");
            sender.drain();
            receiver.drain();
            outsideWire.drain();
            sender.send(groupId, "synthetic after new member joined");
            check(sender.await("ACK").path("message").path("seq").asLong() == 2, "Second sequence missing");
            outsideWire.await("SYNC");
            check(
                api("GET", "/api/chat/conversations/" + groupId + "/messages?after=0", outsider, null, 200).size() == 1,
                "New member read pre-join history"
            );
            outsideWire.drain();
            api("DELETE", "/api/chat/conversations/" + groupId + "/members/" + outsider.id, owner, null, 204);
            outsideWire.await("SYNC");
            api("GET", "/api/chat/conversations/" + groupId + "/messages", outsider, null, 404);
            check(
                api("GET", "/api/chat/conversations", outsider, null, 200).isEmpty(),
                "Removed member still sees group"
            );
            outsideWire.close();
            pass(
                "MEMBER_4",
                "real API add/remove sync, current permission revocation and join history boundary across discovered HTTP nodes"
            );

            long committed = sql("SELECT COUNT(*) FROM chat_message");
            control("PAUSE_REDIS");
            try {
                api("GET", "/api/auth/me", owner, null, 503);
                sender.drain();
                sender.send(groupId, "synthetic must fail during Redis pause");
                check(
                    "UNAVAILABLE".equals(sender.await("ERROR").path("code").asText()),
                    "Redis failure not fail-closed"
                );
                check(sql("SELECT COUNT(*) FROM chat_message") == committed, "Redis failure wrote message");
            } finally {
                control("RESUME_REDIS");
            }
            awaitMe(owner);
            sender.close();
            receiver.close();
            sender = gateway(owner);
            receiver = gateway(peer);
            sender.drain();
            receiver.drain();
            sender.send(groupId, "synthetic after Redis recovery");
            check(sender.await("ACK").path("message").path("seq").asLong() == 3, "Recovery lost persisted sequence");
            receiver.await("SYNC");
            pass(
                "REDIS_5",
                "actual owned Redis pause, Gateway 503 and Netty UNAVAILABLE without SQL write; resume and new connections recover"
            );

            Wire peerA = direct(peer, 42997),
                peerB = direct(peer, 42998);
            api("POST", "/api/auth/logout", peer, null, 200);
            api("GET", "/api/auth/me", peer, null, 401);
            peerA.drain();
            peerB.drain();
            peerA.ping();
            peerB.ping();
            check(
                "AUTH_REQUIRED".equals(peerA.await("ERROR").path("code").asText()),
                "Node A accepted revoked session"
            );
            check(
                "AUTH_REQUIRED".equals(peerB.await("ERROR").path("code").asText()),
                "Node B accepted revoked session"
            );
            pass(
                "SESSION_6",
                "real Cookie logout revokes shared session; existing authenticated connections on both nodes deny PING"
            );

            control("STOP_NODE_A");
            discovery(1);
            sender.close();
            receiver.close();
            peer = login(peer);
            sender = gateway(owner);
            receiver = gateway(peer);
            sender.drain();
            receiver.drain();
            sender.send(groupId, "synthetic while node A is offline");
            check(sender.await("ACK").path("message").path("seq").asLong() == 4, "Failover lost sequence");
            receiver.await("SYNC");
            pass(
                "FAILOVER_7",
                "graceful node A deregistration; new Gateway connections use live node B and persist facts"
            );

            control("RESTART_NODE_A");
            discovery(2);
            check(
                api("GET", "/api/chat/conversations/" + groupId + "/messages?after=0", peer, null, 200).size() == 4,
                "Restart lost facts"
            );
            var reopened = direct(peer, 42997);
            reopened.await("SYNC");
            api(
                "PATCH",
                "/api/chat/conversations/" + groupId,
                owner,
                Map.of("title", "Renamed complete network group"),
                204
            );
            reopened.drain();
            api("DELETE", "/api/chat/conversations/" + groupId, owner, null, 204);
            reopened.await("SYNC");
            api("GET", "/api/chat/conversations/" + groupId + "/messages", peer, null, 404);
            pass(
                "RESTART_8",
                "fresh Boot node A joins discovery, committed history survives, rename/close sync and current history denial"
            );
            System.out.println(
                "PASS CHAT_GATEWAY_ALL actual Boot/Nacos/Redis/Sa-Token/Triple/SQL/WS; no production publication"
            );
        } finally {
            WIRES.forEach(Wire::close);
        }
    }

    private static User register(String handle) throws Exception {
        String password = "synthetic-" + UUID.randomUUID();
        JsonNode result = api(
            "POST",
            "/api/auth/register",
            null,
            Map.of(
                "email",
                handle + "@example.invalid",
                "password",
                password,
                "handle",
                handle,
                "displayName",
                "合成验证用户"
            ),
            200
        );
        return new User(result.path("user").path("id").asText(), handle, password, result.path("tokenValue").asText());
    }

    private static User login(User original) throws Exception {
        JsonNode result = api(
            "POST",
            "/api/auth/login",
            null,
            Map.of("email", original.handle + "@example.invalid", "password", original.password),
            200
        );
        check(original.id.equals(result.path("user").path("id").asText()), "Login identity mismatch");
        return new User(original.id, original.handle, original.password, result.path("tokenValue").asText());
    }

    private static void awaitMe(User user) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try {
                api("GET", "/api/auth/me", user, null, 200);
                return;
            } catch (AssertionError failure) {
                Thread.sleep(300);
            }
        }
        throw new AssertionError("Shared session did not recover after Redis resumed");
    }

    private static JsonNode api(String method, String path, User user, Object body, int status) throws Exception {
        return raw(method, ORIGIN + path, user, body, "999999999999999999", status);
    }

    private static JsonNode raw(String method, String target, User user, Object body, String forgedId, int status)
        throws Exception {
        check(target.startsWith("http://127.0.0.1:"), "Only fixed loopback targets permitted");
        var request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofSeconds(20))
            .header("Origin", ORIGIN);
        if (user != null) request.header("Cookie", "koko-nexus-token=" + user.token);
        if (forgedId != null) request
            .header("X-Koko-User-Id", forgedId)
            .header("X-Koko-Gateway-Key", "synthetic-forged-key");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))
        );
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        check(
            response.statusCode() == status,
            "Unexpected HTTP status method=" + method + " actual=" + response.statusCode() + " expected=" + status
        );
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private static long sql(String query) throws Exception {
        try (
            var connection = DriverManager.getConnection(
                System.getenv("CHAT_GATEWAY_JDBC"),
                System.getenv("CHAT_GATEWAY_SQL_USER"),
                System.getenv("CHAT_GATEWAY_SQL_PASSWORD")
            );
            var statement = connection.prepareStatement(query);
            var rows = statement.executeQuery()
        ) {
            check(rows.next(), "Expected SQL scalar");
            return rows.getLong(1);
        }
    }

    private static void discovery(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            var request = HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:28948/nacos/v1/ns/instance/list?serviceName=koko-nexus-chat&groupName=KOKO_CHAT_GATEWAY_CHECK_20261005_HTTP&healthyOnly=true"
                )
            )
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            var result = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() == 200) {
                var hosts = JSON.readTree(result.body()).path("hosts");
                if (hosts.isArray() && hosts.size() == count) {
                    for (JsonNode host : hosts) {
                        int port = host.path("port").asInt();
                        check("127.0.0.1".equals(host.path("ip").asText()), "HTTP discovery points outside loopback");
                        check(port == 42197 || port == 42198, "RPC/unknown port entered HTTP group");
                        check(
                            host
                                .path("metadata")
                                .path("koko-chat-websocket-port")
                                .asText()
                                .equals(port == 42197 ? "42997" : "42998"),
                            "HTTP/WS metadata mismatch"
                        );
                    }
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Actual HTTP discovery instance count not reached: " + count);
    }

    private static boolean split() throws Exception {
        Thread.sleep(1200);
        return metric(42197) == 1 && metric(42198) == 1;
    }

    private static int metric(int port) throws Exception {
        var response = HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        check(response.statusCode() == 200, "Local metrics unavailable");
        return response
            .body()
            .lines()
            .filter(line -> line.startsWith("koko_chat_sync_connected_users "))
            .map(line -> (int) Double.parseDouble(line.substring(line.indexOf(' ') + 1)))
            .findFirst()
            .orElseThrow();
    }

    private static Wire gateway(User user) throws Exception {
        var wire = new Wire(42190, user, false);
        wire.await("READY");
        return wire;
    }

    private static Wire direct(User user, int port) throws Exception {
        var wire = new Wire(port, user, true);
        wire.await("READY");
        return wire;
    }

    private static void control(String action) throws Exception {
        System.out.println("CONTROL " + action);
        System.out.flush();
        String reply = new BufferedReader(new InputStreamReader(System.in)).readLine();
        check((action + " OK").equals(reply), "Owned orchestration control failed");
    }

    private static void pass(String id, String detail) {
        System.out.println("PASS " + id + ": " + detail);
    }

    private static void check(boolean valid, String detail) {
        if (!valid) throw new AssertionError(detail);
    }

    /** 测试凭据只在内存，不生成 toString 或默认序列化。 */
    private static final class User {

        /** 实际注册产生的合成账号 ID。 */ private final String id;
        /** 合成公开用户名，用于生产身份目录查找。 */ private final String handle;
        /** 本轮随机密码，禁止输出。 */ private final String password;
        /** 真实 Redis 会话令牌，禁止输出。 */ private final String token;

        User(String id, String handle, String password, String token) {
            this.id = id;
            this.handle = handle;
            this.password = password;
            this.token = token;
        }
    }

    private static final class Wire implements WebSocket.Listener {

        /** 本轮真实网络帧，关闭即回收；不写日志正文。 */ private final LinkedBlockingQueue<JsonNode> frames =
            new LinkedBlockingQueue<>();
        /** 分片拼接缓冲，不能跨连接复用。 */ private final StringBuilder partial = new StringBuilder();
        /** 本轮已认证连接。 */ private final WebSocket socket;

        Wire(int port, User user, boolean direct) throws Exception {
            var builder = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("Origin", ORIGIN)
                .header("Cookie", "koko-nexus-token=" + user.token)
                .header("X-Koko-User-Id", direct ? user.id : "999999999999999999")
                .header(
                    "X-Koko-Gateway-Key",
                    direct ? "isolated-chat-gateway-key-not-production" : "synthetic-forged-key"
                );
            socket = builder
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/chat/ws"), this)
                .get(15, TimeUnit.SECONDS);
            WIRES.add(this);
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                try {
                    frames.add(JSON.readTree(partial.toString()));
                } catch (Exception failure) {
                    throw new IllegalStateException("Protocol fixture failed", failure);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        JsonNode await(String type) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < until) {
                var frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.path("type").asText())) return frame;
                if (frame != null && "ERROR".equals(frame.path("type").asText())) throw new AssertionError(
                    "Unexpected protocol code=" + frame.path("code").asText()
                );
            }
            throw new AssertionError("Missing " + type + " frame");
        }

        void drain() throws Exception {
            Thread.sleep(1200);
            frames.clear();
        }

        void send(String conversation, String body) throws Exception {
            socket
                .sendText(
                    JSON.writeValueAsString(
                        Map.of(
                            "type",
                            "SEND",
                            "conversationId",
                            conversation,
                            "clientMessageId",
                            UUID.randomUUID().toString(),
                            "body",
                            body
                        )
                    ),
                    true
                )
                .get(5, TimeUnit.SECONDS);
        }

        void ping() throws Exception {
            socket.sendText("{\"type\":\"PING\"}", true).get(5, TimeUnit.SECONDS);
        }

        void close() {
            socket.abort();
        }
    }
}
