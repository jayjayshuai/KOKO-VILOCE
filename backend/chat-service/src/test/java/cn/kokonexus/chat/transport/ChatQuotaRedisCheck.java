package cn.kokonexus.chat.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 真Redis/Lua与两个独立Netty JVM验收；自然等待120秒，不改时钟或用模拟过期冒充。 */
public final class ChatQuotaRedisCheck {

    /** 只读合成帧，不打印会话或正文。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 仅本轮控制回复，父进程执行确定资源的暂停/恢复。 */
    private static final BufferedReader INPUT = new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) throws Exception {
        Path evidence = Path.of(System.getenv("CHAT_QUOTA_EVIDENCE")).toAbsolutePath().normalize();
        check(
            evidence.startsWith(Path.of("D:/KOKO/.runtime").toAbsolutePath().normalize()),
            "Private workspace evidence required"
        );
        var meters = new SimpleMeterRegistry();
        try (var first = new ChatQuotaRedisFixture(); var second = new ChatQuotaRedisFixture()) {
            var a = new RedisChatConnectionQuota(first.redis, meters);
            var b = new RedisChatConnectionQuota(second.redis, meters);
            check(
                first.redis.keys(RedisChatConnectionQuota.KEY_PREFIX + "*").isEmpty(),
                "Fresh private namespace required"
            );
            var accepted = java.util.Collections.synchronizedList(new ArrayList<String>());
            try (var pool = Executors.newFixedThreadPool(12)) {
                var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 48; index++) {
                    var quota = index % 2 == 0 ? a : b;
                    tasks.add(
                        pool.submit(() -> {
                            String id = UUID.randomUUID().toString();
                            if (quota.acquire(61001, id)) accepted.add(id);
                        })
                    );
                }
                for (var task : tasks) task.get(15, TimeUnit.SECONDS);
            }
            check(
                accepted.size() == 3 && size(first.redis, 61001) == 3,
                "Atomic global admission exceeded three or lost success"
            );
            String unrelated = UUID.randomUUID().toString();
            check(b.acquire(61002, unrelated), "Different user quota collided");
            check(
                a.acquire(61001, accepted.getFirst()) && size(first.redis, 61001) == 3,
                "Same attempt not idempotent"
            );
            check(b.renew(61001, accepted.getFirst()), "Cross-client renewal failed");
            for (String id : accepted) a.release(61001, id);
            check(
                size(first.redis, 61001) == 0 && size(first.redis, 61002) == 1,
                "Release crossed users or leaked original quota"
            );
            b.release(61002, unrelated);
            System.out.println(
                "PASS REDIS_1: 48 concurrent attempts / two independent clients / exactly three / cross-client idempotency and user isolation"
            );

            String old = UUID.randomUUID().toString(),
                replacement = UUID.randomUUID().toString();
            check(a.acquire(61003, old), "Initial lease missing");
            a.release(61003, old);
            check(b.acquire(61003, replacement), "Replacement missing");
            a.release(61003, old);
            check(
                !a.renew(61003, old) && b.renew(61003, replacement) && size(first.redis, 61003) == 1,
                "Old attempt modified new lease"
            );
            try (var connection = first.redis.getConnectionFactory().getConnection()) {
                connection.scriptingCommands().scriptFlush();
            }
            check(b.renew(61003, replacement), "NOSCRIPT recovery failed");
            b.release(61003, replacement);
            first.redis.opsForValue().set(key(61004), "synthetic-corrupt-type");
            try {
                a.acquire(61004, UUID.randomUUID().toString());
                throw new AssertionError("Wrong type admitted");
            } catch (ChatQuotaUnavailableException expected) {
                check("共享聊天配额暂不可用".equals(expected.getMessage()), "Raw Redis error leaked");
            }
            first.redis.delete(key(61004));
            System.out.println(
                "PASS REDIS_2: old release/renew cannot affect replacement; script flush reloads; wrong type fails closed"
            );

            String orphan = UUID.randomUUID().toString();
            check(a.acquire(61005, orphan), "Orphan lease not registered");
            long ttl = first.redis.getExpire(key(61005), TimeUnit.MILLISECONDS);
            check(ttl > 115_000 && ttl <= RedisChatConnectionQuota.LEASE_MILLIS, "Natural lease TTL wrong");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(140),
                progress = System.nanoTime();
            while (Boolean.TRUE.equals(first.redis.hasKey(key(61005))) && System.nanoTime() < deadline) {
                Thread.sleep(500);
                if (System.nanoTime() - progress >= TimeUnit.SECONDS.toNanos(30)) {
                    System.out.println("PROGRESS natural TTL still being observed");
                    System.out.flush();
                    progress = System.nanoTime();
                }
            }
            check(
                !Boolean.TRUE.equals(first.redis.hasKey(key(61005))) && !a.renew(61005, orphan),
                "Natural expiry resurrected"
            );
            check(b.acquire(61005, replacement), "Natural orphan did not recover");
            a.release(61005, orphan);
            check(size(first.redis, 61005) == 1, "Old expiry release removed new slot");
            b.release(61005, replacement);
            System.out.println(
                "PASS REDIS_3: actual 120-second natural TTL / orphan recovers / expired renewal and release fenced"
            );

            try (
                var nodeA = new Node(evidence.resolve("node-a.log"));
                var nodeB = new Node(evidence.resolve("node-b.log"))
            ) {
                check(nodeA.process.pid() != nodeB.process.pid(), "Nodes must be separate JVMs");
                System.out.println("QUOTA_NODE_PIDS " + nodeA.process.pid() + " " + nodeB.process.pid());
                Wire firstWire = new Wire(nodeA.port, 62001),
                    secondWire = new Wire(nodeB.port, 62001),
                    thirdWire = new Wire(nodeA.port, 62001);
                try {
                    try {
                        new Wire(nodeB.port, 62001);
                        throw new AssertionError("Fourth global connection accepted");
                    } catch (java.util.concurrent.ExecutionException denied) {
                        check(
                            denied.getCause() instanceof java.net.http.WebSocketHandshakeException &&
                                ((java.net.http.WebSocketHandshakeException) denied.getCause())
                                    .getResponse()
                                    .statusCode() == 429,
                            "Global denial not 429"
                        );
                    }
                    check(size(first.redis, 62001) == 3, "TCP global slots not three");
                    secondWire.ping();
                    check("PONG".equals(secondWire.next().path("type").asText()), "Valid lease no PONG");
                    firstWire.close();
                    awaitSize(first.redis, 62001, 2);
                    var next = new Wire(nodeB.port, 62001);
                    next.close();
                    secondWire.close();
                    thirdWire.close();
                    awaitSize(first.redis, 62001, 0);
                    System.out.println(
                        "PASS TCP_4: two independent Netty JVMs / global fourth 429 / PING renewal / actual close releases capacity"
                    );
                } finally {
                    firstWire.close();
                    secondWire.close();
                    thirdWire.close();
                }
                var expiredWire = new Wire(nodeA.port, 62002);
                first.redis.delete(key(62002)); // 明确故障夹具，与自然TTL验收不同，不冒称自然过期。
                expiredWire.send();
                check(
                    "CONNECTION_EXPIRED".equals(expiredWire.next().path("code").asText()),
                    "Missing lease reached business"
                );
                check(nodeA.calls() == 0, "Expired connection entered SQL boundary");
                expiredWire.close();
                System.out.println(
                    "PASS TCP_5: removed lease cannot SEND / explicit expired error / zero business calls"
                );

                var pausedWire = new Wire(nodeB.port, 62002);
                String unknown = UUID.randomUUID().toString();
                control("PAUSE");
                try {
                    try {
                        a.acquire(61006, unknown);
                        throw new AssertionError("Paused Redis admitted");
                    } catch (ChatQuotaUnavailableException expected) {
                        /* 真实Redis超时，不推进为成功。 */
                    }
                    pausedWire.ping();
                    check(
                        "UNAVAILABLE".equals(pausedWire.next().path("code").asText()),
                        "Redis timeout did not fail closed"
                    );
                    check(nodeB.calls() == 0, "Redis failure entered business");
                } finally {
                    control("RESUME");
                }
                pausedWire.close();
                a.release(61006, unknown);
                var recovered = new Wire(nodeB.port, 62002);
                recovered.ping();
                check("PONG".equals(recovered.next().path("type").asText()), "Recovery did not renew");
                recovered.close();
                awaitSize(first.redis, 62002, 0);
                System.out.println(
                    "PASS TCP_6: real owned Redis pause/resume / no admission or business on failure / unknown attempt release / reconnect recovery"
                );
            }
            check(first.redis.keys(RedisChatConnectionQuota.KEY_PREFIX + "*").isEmpty(), "Owned quota keys remain");
            System.out.println(
                "PASS CHAT_QUOTA_ALL production Lua / real Redis / two Netty JVMs; identity and SQL are fixtures"
            );
        } finally {
            meters.close();
        }
    }

    private static String key(long user) {
        return RedisChatConnectionQuota.KEY_PREFIX + "{" + user + "}";
    }

    private static long size(StringRedisTemplate redis, long user) {
        return redis.opsForZSet().zCard(key(user));
    }

    private static void awaitSize(StringRedisTemplate redis, long user, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (size(redis, user) != expected && System.nanoTime() < deadline) Thread.sleep(50);
        check(size(redis, user) == expected, "Async release did not converge");
    }

    private static void control(String action) throws Exception {
        System.out.println("CONTROL " + action);
        System.out.flush();
        check((action + " OK").equals(INPUT.readLine()), "Control mismatch");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class Node implements AutoCloseable {

        /** 本轮精确进程句柄。 */ private final Process process;
        /** 不含秘密的控制命令。 */ private final PrintWriter input;
        /** 只传递控制响应，不打印业务或凭据。 */ private final LinkedBlockingQueue<String> output =
            new LinkedBlockingQueue<>();
        /** 连续保存独立节点日志。 */ private final Thread reader;
        /** 节点随机环回端口。 */ private final int port;

        Node(Path file) throws Exception {
            process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                ChatQuotaNodeCheck.class.getName()
            )
                .redirectErrorStream(true)
                .start();
            input = new PrintWriter(process.getOutputStream(), true);
            reader = Thread.ofPlatform().start(() -> {
                try (
                    var lines = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    var log = Files.newBufferedWriter(file)
                ) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        log.write(line);
                        log.newLine();
                        log.flush();
                        if (line.startsWith("QUOTA_")) output.add(line);
                    }
                } catch (Exception failure) {
                    output.add("QUOTA_LOG_FAILED");
                }
            });
            String ready = output.poll(30, TimeUnit.SECONDS);
            if (ready == null || !ready.startsWith("QUOTA_NODE_READY ")) {
                close();
                throw new AssertionError("Node did not start");
            }
            port = Integer.parseInt(ready.substring(17));
        }

        int calls() throws Exception {
            input.println("STATUS");
            String line = output.poll(5, TimeUnit.SECONDS);
            check(line != null && line.startsWith("QUOTA_BUSINESS_CALLS "), "Status unavailable");
            return Integer.parseInt(line.substring(21));
        }

        @Override
        public void close() throws Exception {
            input.println("QUIT");
            input.close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
            }
            reader.join(5000);
            check(process.exitValue() == 0, "Owned node not graceful");
        }
    }

    private static final class Wire implements WebSocket.Listener {

        /** 网络帧只在内存读取。 */ private final LinkedBlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        /** 分片文本重组。 */ private final StringBuilder partial = new StringBuilder();
        /** 本轮真实TCP连接。 */ private final WebSocket socket;

        Wire(int port, long user) throws Exception {
            socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .header("Origin", "http://127.0.0.1")
                .header("X-Koko-Gateway-Key", ChatQuotaNodeCheck.FIXTURE_KEY)
                .header("X-Koko-User-Id", Long.toString(user))
                .header("Cookie", "koko-nexus-token=test-quota-session")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/chat/ws"), this)
                .get(5, TimeUnit.SECONDS);
            check("READY".equals(next().path("type").asText()), "Connection no READY");
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence value, boolean last) {
            partial.append(value);
            if (last) {
                try {
                    frames.add(JSON.readTree(partial.toString()));
                } catch (Exception failure) {
                    throw new IllegalStateException("Invalid fixture frame", failure);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        JsonNode next() throws Exception {
            JsonNode frame = frames.poll(6, TimeUnit.SECONDS);
            check(frame != null, "Expected frame missing");
            return frame;
        }

        void ping() {
            socket.sendText("{\"type\":\"PING\"}", true).join();
        }

        void send() {
            socket
                .sendText(
                    "{\"type\":\"SEND\",\"conversationId\":\"x\",\"clientMessageId\":\"y\",\"body\":\"synthetic\"}",
                    true
                )
                .join();
        }

        void close() {
            socket.abort();
        }
    }
}
