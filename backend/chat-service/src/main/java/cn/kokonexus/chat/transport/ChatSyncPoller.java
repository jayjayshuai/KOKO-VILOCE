package cn.kokonexus.chat.transport;

import cn.kokonexus.chat.domain.ChatSyncRevision;
import cn.kokonexus.chat.persistence.ChatSyncMapper;
import cn.kokonexus.common.diagnostics.SafeFailureDetails;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 每节点单线程观察已提交用户版本；独立于 EventLoop，无 Redis 瞬时通知依赖。 */
@Component
public class ChatSyncPoller implements SmartLifecycle {

    /** 不输出 SQL 异常消息、用户 ID、正文或密钥。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatSyncPoller.class);
    /** 单条 IN 查询上限，500 个网络连接最多五批。 */
    private static final int BATCH_SIZE = 100;
    /** 用户级版本事实，只查询本节点认证用户。 */
    private final ChatSyncMapper mapper;
    /** 本机连接快照及定向失效提示。 */
    private final ChatSocketServer sockets;
    /** 固定轮询间隔，毫秒；默认 1000，不因失败立即忙循环。 */
    private final long intervalMillis;
    /** 每批 SQL 读取失败计数，不能将失败当版本 0。 */
    private final Counter failures;
    /** 收到变化的用户数，仅用于容量观察，不声称帧已送达。 */
    private final Counter changedUsers;
    /** 工作线程；定长单任务，不积压每轮独立队列。 */
    private ScheduledExecutorService executor;
    /** 生命周期状态；长 SQL 返回后仍须验证停止围栏。 */
    private volatile boolean running;
    /** 启停轮次，旧线程不能在新启动轮次发送通知。 */
    private volatile long lifecycleRevision;
    /** 最近一次成功完整扫描的单调时钟；失败/无连接不冒充成功。 */
    private volatile long lastSuccessNanos;
    /** 上一轮是否失败；只在状态转换记录一次安全原因。 */
    private volatile boolean failed;
    /** 最近扫描的已认证在线用户数，供告警区分空闲节点与同步停滞。 */
    private volatile int connectedUserCount;

    public ChatSyncPoller(
        ChatSyncMapper mapper,
        ChatSocketServer sockets,
        MeterRegistry meters,
        @Value("${koko.chat.sync-poll-interval-ms:1000}") long intervalMillis
    ) {
        if (intervalMillis < 250 || intervalMillis > 10000) {
            throw new IllegalStateException("聊天同步轮询间隔必须为 250～10000 毫秒");
        }
        this.mapper = mapper;
        this.sockets = sockets;
        this.intervalMillis = intervalMillis;
        failures = meters.counter("koko.chat.sync.poll.failures");
        changedUsers = meters.counter("koko.chat.sync.changed.users");
        Gauge.builder("koko.chat.sync.failed", this, value -> value.failed ? 1 : 0).register(meters);
        Gauge.builder("koko.chat.sync.connected.users", this, value -> value.connectedUserCount).register(meters);
        Gauge.builder("koko.chat.sync.running", this, value -> value.running ? 1 : 0).register(meters);
        Gauge.builder("koko.chat.sync.last.success.age.seconds", this, value ->
            value.lastSuccessNanos == 0 ? -1 : (System.nanoTime() - value.lastSuccessNanos) / 1_000_000_000.0
        ).register(meters);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        var observed = new HashMap<Long, Long>(); // 每次启动独占，不复用旧节点观察进度。
        long revision = ++lifecycleRevision;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread worker = new Thread(task, "chat-sync-poller");
            worker.setDaemon(true);
            return worker;
        });
        running = true;
        executor.scheduleWithFixedDelay(
            () -> scan(observed, () -> running && lifecycleRevision == revision),
            0,
            intervalMillis,
            TimeUnit.MILLISECONDS
        );
    }

    /** 包内供生产工作线程与局部测试调用；读失败不推进失败批次的观察值。 */
    void scan(Map<Long, Long> observed, BooleanSupplier current) {
        if (!current.getAsBoolean()) return;
        try {
            var connected = sockets.connectedUsers();
            connectedUserCount = connected.size();
            observed.keySet().retainAll(connected);
            if (connected.isEmpty()) return;
            List<Long> users = connected.stream().sorted().toList();
            for (int offset = 0; offset < users.size(); offset += BATCH_SIZE) {
                if (!current.getAsBoolean()) return;
                List<Long> batch = users.subList(offset, Math.min(users.size(), offset + BATCH_SIZE));
                var values = new HashMap<Long, Long>();
                List<ChatSyncRevision> result = mapper.revisions(batch);
                if (result == null) throw new IllegalStateException("聊天同步版本读取未完成");
                for (ChatSyncRevision row : result) {
                    if (
                        row == null ||
                        row.getUserId() == null ||
                        row.getRevision() == null ||
                        row.getRevision() <= 0 ||
                        !batch.contains(row.getUserId()) ||
                        values.put(row.getUserId(), row.getRevision()) != null
                    ) {
                        throw new IllegalStateException("聊天同步版本投影无效");
                    }
                }
                if (!current.getAsBoolean()) return;
                var changed = new HashSet<Long>();
                for (long userId : batch) {
                    long revision = values.getOrDefault(userId, 0L);
                    Long previous = observed.get(userId);
                    if (previous == null || previous.longValue() != revision) changed.add(userId);
                }
                // 通知异常也不能推进观察值；普通发送失败由慢连接关闭与客户端事实补拉恢复。
                sockets.syncUsers(changed);
                for (long userId : batch) observed.put(userId, values.getOrDefault(userId, 0L));
                changedUsers.increment(changed.size());
            }
            if (!current.getAsBoolean()) return;
            lastSuccessNanos = System.nanoTime();
            if (failed) LOG.info("Chat sync revision polling recovered");
            failed = false;
        } catch (RuntimeException failure) {
            if (!current.getAsBoolean()) return;
            failures.increment();
            if (!failed) LOG.warn("Chat sync revision polling unavailable: {}", SafeFailureDetails.describe(failure));
            failed = true;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        connectedUserCount = 0;
        lifecycleRevision++;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("Chat sync worker stop pending");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 100;
    }
}
