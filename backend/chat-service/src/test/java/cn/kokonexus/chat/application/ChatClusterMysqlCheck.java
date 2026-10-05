package cn.kokonexus.chat.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.chat.transport.ChatClusterNodeCheck;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立入口：限权真 MySQL、生产事务/SQL、两个独立 Netty JVM。凭据只从环境读取。 */
public final class ChatClusterMysqlCheck {

    /** 只序列化合成协议夹具，不输出凭据。 */
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        var fixture = new ChatClusterFixture();
        var flyway = Flyway.configure()
            .dataSource(fixture.source)
            .table("chat_flyway_schema_history")
            .locations("classpath:db/migration")
            .load();
        check(flyway.migrate().migrationsExecuted == 4, "Expected four new migrations in empty isolated schema");
        flyway.validate();
        sqlChecks(fixture);
        networkChecks(fixture);
        System.out.println(
            "PASS CHAT_CLUSTER_ALL: actual SQL and two separate Netty JVMs; authentication is fixture-only"
        );
    }

    private static void sqlChecks(ChatClusterFixture f) throws Exception {
        try {
            f.writer.changed(List.of(9101L));
            throw new AssertionError("MANDATORY writer accepted nontransactional call");
        } catch (org.springframework.transaction.IllegalTransactionStateException expected) {
            check(revision(f, 9101) == 0, "Nontransactional call wrote revision");
        }
        var group = f.service.create(identity(9101), List.of(identity(9102)), "SQL test group", true);
        check(revision(f, 9101) == 1 && revision(f, 9102) == 1, "Creation versions missing");
        String request = UUID.randomUUID().toString();
        var first = f.service.send(9101, group.getId(), request, "synthetic first message");
        var retry = f.service.send(9101, group.getId(), request, "synthetic first message");
        check(
            first.getId().equals(retry.getId()) && revision(f, 9101) == 2 && revision(f, 9102) == 2,
            "Idempotent resend changed message or versions"
        );
        try {
            f.service.send(9101, group.getId(), request, "synthetic changed body");
            throw new AssertionError("Idempotency conflict accepted");
        } catch (IllegalStateException expected) {
            check(revision(f, 9101) == 2, "Conflicting message advanced revision");
        }
        System.out.println("PASS SQL_1: Flyway V1-V4, MANDATORY proxy, create/send/retry/conflict revisions");

        long base = revision(f, 9101);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 24; index++) tasks.add(
                executor.submit(() ->
                    f.service.send(9101, group.getId(), UUID.randomUUID().toString(), "synthetic concurrent message")
                )
            );
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        check(revision(f, 9101) == base + 24 && revision(f, 9102) == base + 24, "Concurrent revisions lost");
        check(f.service.history(9102, group.getId(), null, 0L, 100).size() == 25, "Concurrent messages lost");
        f.service.read(9102, group.getId(), 25);
        check(revision(f, 9102) == base + 25, "Read advance not registered");
        f.service.read(9102, group.getId(), 1);
        check(revision(f, 9102) == base + 25, "Old read caused needless version");
        System.out.println("PASS SQL_2: 24 concurrent sends, contiguous facts and per-user versions, monotonic reads");

        var other = f.service.create(identity(9102), List.of(identity(9101)), "Reverse owner group", true);
        base = revision(f, 9101);
        long peerBase = revision(f, 9102);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 20; index++) {
                boolean original = index % 2 == 0;
                tasks.add(
                    executor.submit(() ->
                        f.service.send(
                            original ? 9101 : 9102,
                            original ? group.getId() : other.getId(),
                            UUID.randomUUID().toString(),
                            "synthetic multi-conversation"
                        )
                    )
                );
            }
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        check(
            revision(f, 9101) == base + 20 && revision(f, 9102) == peerBase + 20,
            "Sorted version locking lost updates"
        );
        System.out.println("PASS SQL_3: overlapping users in two conversations, reverse owners, 20 concurrent commits");

        long originalSeq = f.jdbc.queryForObject(
            "SELECT last_seq FROM chat_conversation WHERE id=?",
            Long.class,
            group.getId()
        );
        base = revision(f, 9101);
        peerBase = revision(f, 9102);
        f.jdbc.execute(
            "CREATE TRIGGER cluster_sync_fault BEFORE UPDATE ON chat_sync_revision FOR EACH ROW " +
                "BEGIN IF NEW.user_id = 9102 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic revision failure'; END IF; END"
        );
        try {
            try {
                f.service.send(9101, group.getId(), UUID.randomUUID().toString(), "synthetic must roll back");
                throw new AssertionError("Revision SQL fault did not roll back send");
            } catch (DataAccessException expected) {
                check(
                    revision(f, 9101) == base && revision(f, 9102) == peerBase,
                    "Earlier version row did not roll back"
                );
                check(
                    f.jdbc.queryForObject(
                        "SELECT last_seq FROM chat_conversation WHERE id=?",
                        Long.class,
                        group.getId()
                    ) == originalSeq,
                    "Sequence survived failed version registration"
                );
                check(
                    f.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",
                        Long.class,
                        group.getId()
                    ) == originalSeq,
                    "Message survived failed version registration"
                );
            }
        } finally {
            f.jdbc.execute("DROP TRIGGER cluster_sync_fault");
        }
        System.out.println("PASS SQL_4: real version SQL failure rolls back earlier version, message and sequence");

        var tx = new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source)
        );
        base = revision(f, 9101);
        final long committedBase = base;
        try {
            tx.executeWithoutResult(status -> {
                f.service.rename(9101, group.getId(), "synthetic uncommitted title");
                // 独立连接必须看不到尚未提交的用户版本；DriverManagerDataSource 每次创建新连接。
                try (
                    var connection = f.source.getConnection();
                    var query = connection.prepareStatement(
                        "SELECT revision FROM chat_sync_revision WHERE user_id=9101"
                    )
                ) {
                    try (var rows = query.executeQuery()) {
                        check(rows.next() && rows.getLong(1) == committedBase, "Uncommitted revision became visible");
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException("Isolation check failed", failure);
                }
                throw new IllegalStateException("synthetic outer rollback");
            });
        } catch (IllegalStateException expected) {
            check(revision(f, 9101) == base, "Outer rollback did not restore revision");
        }
        check(
            "SQL test group".equals(
                f.jdbc.queryForObject("SELECT title FROM chat_conversation WHERE id=?", String.class, group.getId())
            ),
            "Outer rollback did not restore title"
        );
        System.out.println("PASS SQL_5: separate connection visibility and actual outer transaction rollback");

        f.service.add(9101, group.getId(), identity(9103));
        check(revision(f, 9103) == 1, "New member not notified");
        long removedBase = revision(f, 9103);
        f.service.remove(9101, group.getId(), 9103);
        check(revision(f, 9103) == removedBase + 1, "Removed member not notified");
        try {
            f.service.history(9103, group.getId(), null, null, 10);
            throw new AssertionError("Removed member could read history");
        } catch (ResourceNotFoundException expected) {
            /* 当前权限确实拒绝。 */
        }
        long ownerBase = revision(f, 9101),
            targetBase = revision(f, 9102);
        f.safety.block(9101, identity(9102));
        check(revision(f, 9101) == ownerBase + 1 && revision(f, 9102) == targetBase, "Block leaked signal to peer");
        f.safety.unblock(9101, 9102);
        check(revision(f, 9101) == ownerBase + 2, "Unblock not registered");
        f.service.close(9101, group.getId());
        check(
            revision(f, 9101) == ownerBase + 3 && revision(f, 9102) == targetBase + 1,
            "Closed group recipients missing"
        );
        System.out.println(
            "PASS SQL_6: add/remove including removed recipient, permission revocation, private block/unblock and close"
        );
        repeatableReadMemberChecks(f);
    }

    /** 预先建立 RR 快照，再让另一连接提交成员变化，不能读旧成员或漏掉新增收件人。 */
    private static void repeatableReadMemberChecks(ChatClusterFixture f) throws Exception {
        var group = f.service.create(identity(9401), List.of(identity(9402)), "RR membership fixture", true);
        var tx = new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.source)
        );
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var worker = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                f.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_member WHERE conversation_id=?",
                    Long.class,
                    group.getId()
                );
                awaitCommit(worker.submit(() -> f.service.add(9401, group.getId(), identity(9403))));
                f.service.rename(9401, group.getId(), "RR current recipients");
            });
            check(revision(f, 9403) == 2, "Existing RR snapshot missed newly committed sync recipient");
            try {
                tx.executeWithoutResult(status -> {
                    check(
                        f.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM chat_member WHERE conversation_id=? AND user_id=9402",
                            Long.class,
                            group.getId()
                        ) == 1,
                        "RR removal fixture missing member"
                    );
                    awaitCommit(worker.submit(() -> f.service.remove(9401, group.getId(), 9402)));
                    f.service.history(9402, group.getId(), null, 0L, 100);
                });
                throw new AssertionError("Existing RR snapshot retained removed member history permission");
            } catch (ResourceNotFoundException expected) {
                /* 当前读必须拒绝，不能使用预先建立的旧快照授予权限。 */
            }
            f.service.add(9401, group.getId(), identity(9402));
            try {
                tx.executeWithoutResult(status -> {
                    f.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chat_member WHERE conversation_id=?",
                        Long.class,
                        group.getId()
                    );
                    awaitCommit(worker.submit(() -> f.service.remove(9401, group.getId(), 9402)));
                    f.service.send(
                        9402,
                        group.getId(),
                        UUID.randomUUID().toString(),
                        "synthetic removed member must fail"
                    );
                });
                throw new AssertionError("Existing RR snapshot retained removed member send permission");
            } catch (ResourceNotFoundException expected) {
                check(
                    f.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",
                        Long.class,
                        group.getId()
                    ) == 0,
                    "Removed member message persisted"
                );
            }
        }
        System.out.println(
            "PASS SQL_7: real preexisting RR snapshot, concurrent membership commits, current recipients and revoked history/send"
        );
    }

    private static void awaitCommit(java.util.concurrent.Future<?> task) {
        try {
            task.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Concurrent fixture commit failed", failure);
        }
    }

    private static long revision(ChatClusterFixture f, long userId) {
        var rows = f.sessions.getMapper(cn.kokonexus.chat.persistence.ChatSyncMapper.class).revisions(List.of(userId));
        return rows.isEmpty() ? 0 : rows.getFirst().getRevision();
    }

    private static void networkChecks(ChatClusterFixture f) throws Exception {
        String roomId = f.service
            .create(identity(9201), List.of(identity(9202)), "Network fixture group", true)
            .getId();
        Path evidence = Path.of(System.getenv("CHAT_CLUSTER_EVIDENCE_DIR")).toAbsolutePath().normalize();
        check(
            evidence.startsWith(Path.of("D:/KOKO/.runtime").toAbsolutePath().normalize()),
            "Evidence must stay in private runtime"
        );
        Files.createDirectories(evidence);
        try (
            var first = new Node(evidence.resolve("node-a.log"));
            var second = new Node(evidence.resolve("node-b.log"))
        ) {
            check(first.process.pid() != second.process.pid(), "Cluster nodes must be independent processes");
            System.out.println("CLUSTER_PROCESS_IDS A=" + first.process.pid() + " B=" + second.process.pid());
            var sender = new Wire(first.port, 9201);
            var receiver = new Wire(second.port, 9202);
            var outsider = new Wire(second.port, 9300);
            try {
                sender.await("READY");
                receiver.await("READY");
                outsider.await("READY");
                sender.await("SYNC");
                receiver.await("SYNC");
                outsider.await("SYNC");
                outsider.drain();
                receiver.drain();
                sender.drain();
                String request = UUID.randomUUID().toString();
                sender.send(roomId, request, "synthetic cross-node message");
                check(sender.await("ACK").path("message").path("seq").asLong() == 1, "Missing persisted ACK");
                JsonNode signal = receiver.await("SYNC");
                check(
                    signal.size() == 1 && signal.path("type").asText().equals("SYNC"),
                    "Signal included private content"
                );
                check(
                    f.service.history(9202, roomId, null, 0L, 100).size() == 1,
                    "Receiver cannot reload committed fact"
                );
                check(
                    outsider.frames.poll(700, TimeUnit.MILLISECONDS) == null,
                    "Unrelated user received change signal"
                );
                System.out.println(
                    "PASS TCP_1: send on JVM A, targeted body-free SYNC on JVM B, real SQL history; unrelated user untouched"
                );

                second.command("FAIL_SCAN");
                second.awaitFailed(true);
                receiver.drain();
                sender.drain();
                sender.send(roomId, UUID.randomUUID().toString(), "synthetic committed during observer SQL failure");
                sender.await("ACK");
                check(receiver.frames.poll(700, TimeUnit.MILLISECONDS) == null, "Failed scan advanced observer");
                second.command("RECOVER_SCAN");
                receiver.await("SYNC");
                second.awaitFailed(false);
                check(
                    f.service.history(9202, roomId, null, 0L, 100).size() == 2,
                    "Recovered observer lost message fact"
                );
                System.out.println(
                    "PASS TCP_2: real query error on observer B, A commits, B recovers persisted revision without new send"
                );

                receiver.drain();
                f.service.rename(9201, roomId, "Renamed network fixture");
                receiver.await("SYNC");
                check(
                    "Renamed network fixture".equals(f.service.list(9202, null, 100).getFirst().getTitle()),
                    "Rename not queryable"
                );
                receiver.drain();
                f.service.remove(9201, roomId, 9202);
                receiver.await("SYNC");
                check(f.service.list(9202, null, 100).isEmpty(), "Removed user still has list access");
                try {
                    f.service.history(9202, roomId, null, 0L, 100);
                    throw new AssertionError("Removed user retained history");
                } catch (ResourceNotFoundException expected) {
                    /* 提示不提供权限。 */
                }
                System.out.println(
                    "PASS TCP_3: rename/removal reaches remote node, removed member receives prompt but real SQL denies history"
                );
            } finally {
                sender.close();
                receiver.close();
                outsider.close();
            }
        }
        // 所有旧节点均已优雅退出；离线期间重新入群并提交事实，新节点不能复用旧观察值。
        f.service.add(9201, roomId, identity(9202));
        f.service.send(9201, roomId, UUID.randomUUID().toString(), "synthetic offline committed message");
        try (
            var restarted = new Node(evidence.resolve("node-restarted.log"));
            var receiver = new Wire(restarted.port, 9202)
        ) {
            receiver.await("READY");
            receiver.await("SYNC");
            var facts = f.service.history(9202, roomId, null, 2L, 100);
            check(
                facts.size() == 1 && facts.getFirst().getSeq() == 3,
                "Restart lost offline fact or exposed pre-join history"
            );
            check(
                f.service.history(9202, roomId, null, 0L, 100).size() == 1,
                "Rejoined member can read pre-join history"
            );
            System.out.println(
                "PASS TCP_4: fresh JVM after graceful shutdown, offline commit, READY/SYNC and rejoin history boundary"
            );
        }
    }

    private static ChatIdentity identity(long userId) {
        return new ChatIdentity(Long.toString(userId), "test-user-" + userId, "合成用户");
    }

    private static void check(boolean valid, String detail) {
        if (!valid) throw new AssertionError(detail);
    }

    private static final class Node implements AutoCloseable {

        /** 本夹具创建的独立 JVM；只允许关闭这个 Process 对象。 */
        private final Process process;
        /** 只发送无密钥测试控制命令。 */
        private final PrintWriter commands;
        /** 节点控制响应，不收集聊天正文或凭据。 */
        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
        /** 各节点独立日志读取线程，关闭时回收。 */
        private final Thread reader;
        /** 本机随机监听端口，不监听公网。 */
        private final int port;

        Node(Path log) throws Exception {
            process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xms32m",
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                ChatClusterNodeCheck.class.getName()
            )
                .redirectErrorStream(true)
                .start();
            commands = new PrintWriter(process.getOutputStream(), true);
            reader = Thread.ofPlatform()
                .name("chat-check-node-log")
                .start(() -> {
                    try (
                        var lines = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        var file = Files.newBufferedWriter(log)
                    ) {
                        String line;
                        while ((line = lines.readLine()) != null) {
                            file.write(line);
                            file.newLine();
                            file.flush();
                            if (line.startsWith("CLUSTER_")) output.add(line);
                        }
                    } catch (Exception failure) {
                        output.add("CLUSTER_LOG_FAILURE");
                    }
                });
            int bound;
            try {
                String ready = output.poll(30, TimeUnit.SECONDS);
                check(
                    ready != null && ready.startsWith("CLUSTER_NODE_READY port="),
                    "Node failed to start; inspect private node log"
                );
                bound = Integer.parseInt(ready.substring(24));
            } catch (Throwable failure) {
                close();
                throw failure;
            }
            port = bound;
        }

        void command(String value) throws Exception {
            commands.println(value);
            awaitLine("CLUSTER_COMMAND_DONE " + value);
        }

        void awaitFailed(boolean expected) throws Exception {
            for (int index = 0; index < 40; index++) {
                commands.println("STATUS");
                String status = awaitLinePrefix("CLUSTER_SCAN_FAILED ");
                awaitLine("CLUSTER_COMMAND_DONE STATUS");
                if (status.endsWith(expected ? "1" : "0")) return;
                Thread.sleep(50);
            }
            throw new AssertionError("Observer fault state not reached");
        }

        void awaitLine(String expected) throws Exception {
            check(expected.equals(awaitLinePrefix(expected)), "Node command mismatch");
        }

        String awaitLinePrefix(String prefix) throws Exception {
            String line = output.poll(10, TimeUnit.SECONDS);
            check(line != null && line.startsWith(prefix), "Node command timeout/mismatch");
            return line;
        }

        @Override
        public void close() throws Exception {
            commands.println("QUIT");
            commands.close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
            }
            reader.join(5000);
            check(!process.isAlive(), "Owned node process still running");
            check(process.exitValue() == 0, "Owned node did not stop cleanly; inspect its private log");
        }
    }

    private static final class Wire implements WebSocket.Listener, AutoCloseable {

        /** 真实网络帧，仅含合成用户/消息夹具。 */
        private final LinkedBlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        /** 重组分片文本，不向文件写入凭据。 */
        private final StringBuilder partial = new StringBuilder();
        /** 仅连接本地测试 JVM 的 WebSocket。 */
        private final WebSocket socket;

        Wire(int port, long userId) throws Exception {
            socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .header("Origin", "http://127.0.0.1")
                .header("X-Koko-Gateway-Key", ChatClusterNodeCheck.FIXTURE_KEY)
                .header("X-Koko-User-Id", Long.toString(userId))
                .header("Cookie", "koko-nexus-token=test-device-" + userId)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/chat/ws"), this)
                .get(5, TimeUnit.SECONDS);
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
                    throw new IllegalStateException("Fixture protocol invalid", failure);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        JsonNode await(String type) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < until) {
                JsonNode frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.path("type").asText())) return frame;
                if (frame != null && "ERROR".equals(frame.path("type").asText())) throw new AssertionError(
                    "Unexpected protocol ERROR"
                );
            }
            throw new AssertionError("Missing " + type + " frame");
        }

        void drain() throws Exception {
            Thread.sleep(400);
            frames.clear();
        }

        void send(String roomId, String request, String body) throws Exception {
            socket
                .sendText(
                    JSON.writeValueAsString(
                        Map.of("type", "SEND", "conversationId", roomId, "clientMessageId", request, "body", body)
                    ),
                    true
                )
                .join();
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
