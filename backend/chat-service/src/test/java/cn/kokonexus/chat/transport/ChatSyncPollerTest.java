package cn.kokonexus.chat.transport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.chat.domain.ChatSyncRevision;
import cn.kokonexus.chat.persistence.ChatSyncMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** 观察进度、批量边界与故障关闭；Mapper 桩不证明 MySQL 多事务行为。 */
class ChatSyncPollerTest {

    /** SQL 桩只证明观察规则；真实 SQL 验证另有隔离入口。 */
    private final ChatSyncMapper mapper = mock(ChatSyncMapper.class);
    /** 本机用户/通知桩，不冒充真实网络。 */
    private final ChatSocketServer sockets = mock(ChatSocketServer.class);
    /** 无外部推送的本地指标注册表。 */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** 每个测试独立的生产轮询器。 */
    private final ChatSyncPoller poller = new ChatSyncPoller(mapper, sockets, meters, 1000);

    @Test
    void coalescesVersionsTargetsOnlyChangedUsersAndRetiresDisconnectedProgress() {
        var observed = new HashMap<Long, Long>();
        when(sockets.connectedUsers()).thenReturn(Set.of(7L, 42L));
        when(mapper.revisions(List.of(7L, 42L))).thenReturn(List.of(row(7, 1)));
        poller.scan(observed, () -> true);
        verify(sockets).syncUsers(Set.of(7L, 42L));
        assertThat(observed).containsEntry(7L, 1L).containsEntry(42L, 0L);
        clearInvocations(sockets);
        poller.scan(observed, () -> true);
        verify(sockets).syncUsers(Set.of());
        when(mapper.revisions(List.of(7L, 42L))).thenReturn(List.of(row(7, 12)));
        poller.scan(observed, () -> true);
        verify(sockets).syncUsers(Set.of(7L));
        when(sockets.connectedUsers()).thenReturn(Set.of(42L));
        when(mapper.revisions(List.of(42L))).thenReturn(List.of());
        poller.scan(observed, () -> true);
        assertThat(observed).containsOnlyKeys(42L);
    }

    @Test
    void readFailurePreservesProgressAndRecoveryReplaysChange() {
        var observed = new HashMap<>(java.util.Map.of(7L, 4L));
        when(sockets.connectedUsers()).thenReturn(Set.of(7L));
        when(mapper.revisions(List.of(7L))).thenThrow(new IllegalStateException("synthetic database unavailable"));
        poller.scan(observed, () -> true);
        assertThat(observed).containsEntry(7L, 4L);
        verify(sockets, never()).syncUsers(anySet());
        assertThat(meters.get("koko.chat.sync.poll.failures").counter().count()).isEqualTo(1);
        assertThat(meters.get("koko.chat.sync.failed").gauge().value()).isEqualTo(1);
        doReturn(List.of(row(7, 9)))
            .when(mapper)
            .revisions(List.of(7L));
        poller.scan(observed, () -> true);
        verify(sockets).syncUsers(Set.of(7L));
        assertThat(observed).containsEntry(7L, 9L);
        assertThat(meters.get("koko.chat.sync.failed").gauge().value()).isZero();
    }

    @Test
    void everyReadIsBoundedToOneHundredAndEmptyNodeDoesNotQuery() {
        when(sockets.connectedUsers()).thenReturn(Set.of());
        poller.scan(new HashMap<>(), () -> true);
        verifyNoInteractions(mapper);
        when(sockets.connectedUsers()).thenReturn(Set.copyOf(LongStream.rangeClosed(1, 500).boxed().toList()));
        when(mapper.revisions(anyList())).thenReturn(List.of());
        poller.scan(new HashMap<>(), () -> true);
        verify(mapper, times(5)).revisions(argThat(batch -> batch.size() == 100));
    }

    @Test
    void stoppedRoundNeverPushesLateSqlResult() {
        var current = new AtomicBoolean(true);
        var observed = new HashMap<Long, Long>();
        when(sockets.connectedUsers()).thenReturn(Set.of(7L));
        when(mapper.revisions(anyList())).thenAnswer(call -> {
            current.set(false);
            return List.of(row(7, 2));
        });
        poller.scan(observed, current::get);
        verify(sockets, never()).syncUsers(anySet());
        assertThat(observed).isEmpty();
    }

    @Test
    void invalidProjectionDoesNotAdvanceOrNotify() {
        var observed = new HashMap<Long, Long>();
        when(sockets.connectedUsers()).thenReturn(Set.of(7L));
        for (var invalid : List.of(List.of(row(7, 0)), List.of(row(8, 1)), List.of(row(7, 1), row(7, 2)))) {
            when(mapper.revisions(anyList())).thenReturn(invalid);
            poller.scan(observed, () -> true);
            assertThat(observed).isEmpty();
        }
        verify(sockets, never()).syncUsers(anySet());
    }

    @Test
    void invalidIntervalsRejectStartup() {
        for (long interval : List.of(0L, 249L, 10001L)) {
            assertThatThrownBy(() -> new ChatSyncPoller(mapper, sockets, meters, interval)).isInstanceOf(
                IllegalStateException.class
            );
        }
    }

    private static ChatSyncRevision row(long userId, long revision) {
        var row = new ChatSyncRevision();
        row.setUserId(userId);
        row.setRevision(revision);
        return row;
    }

    @Test
    void partialBatchFailureOnlyPreservesSuccessfullyNotifiedProgress() {
        var observed = new HashMap<Long, Long>();
        var users = Set.copyOf(LongStream.rangeClosed(1, 201).boxed().toList());
        when(sockets.connectedUsers()).thenReturn(users);
        when(mapper.revisions(anyList())).thenAnswer(call -> {
            List<Long> batch = call.getArgument(0);
            if (batch.getFirst() == 101) throw new IllegalStateException("synthetic second batch fault");
            return batch
                .stream()
                .map(id -> row(id, 1))
                .toList();
        });
        poller.scan(observed, () -> true);
        assertThat(observed).hasSize(100);
        assertThat(meters.get("koko.chat.sync.last.success.age.seconds").gauge().value()).isEqualTo(-1);
        clearInvocations(sockets);
        doAnswer(call ->
            ((List<Long>) call.getArgument(0))
                .stream()
                .map(id -> row(id, 1))
                .toList()
        )
            .when(mapper)
            .revisions(anyList());
        poller.scan(observed, () -> true);
        assertThat(observed).hasSize(201);
        verify(sockets).syncUsers(Set.of());
        verify(sockets).syncUsers(Set.of(201L));
        verify(sockets).syncUsers(Set.copyOf(LongStream.rangeClosed(101, 200).boxed().toList()));
        assertThat(meters.get("koko.chat.sync.changed.users").counter().count()).isEqualTo(201);
        assertThat(meters.get("koko.chat.sync.connected.users").gauge().value()).isEqualTo(201);
    }

    @Test
    void actualWorkerStopFencesLateResultAndRestartStartsFreshObservation() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(sockets.connectedUsers()).thenReturn(Set.of(7L));
        when(mapper.revisions(anyList())).thenAnswer(call -> {
            entered.countDown();
            // 模拟 JDBC 不响应线程中断，只在驱动实际返回后检查轮次。
            while (release.getCount() != 0) {
                try {
                    release.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    /* 保留晚返回场景。 */
                }
            }
            return List.of(row(7, 2));
        });
        poller.start();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var stopping = executor.submit((Runnable) poller::stop);
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (poller.isRunning() && System.nanoTime() < until) Thread.sleep(10);
            assertThat(poller.isRunning()).isFalse();
            release.countDown();
            stopping.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(sockets, never()).syncUsers(anySet());
            assertThat(meters.get("koko.chat.sync.running").gauge().value()).isZero();
            doReturn(List.of(row(7, 2)))
                .when(mapper)
                .revisions(anyList());
            poller.start();
            verify(sockets, timeout(3000)).syncUsers(Set.of(7L));
        } finally {
            release.countDown();
            poller.stop();
        }
    }

    @Test
    void queryErrorLogsTypesButNotRawSensitiveMessage() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatSyncPoller.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        String canary = "synthetic-sensitive-database-message";
        try {
            when(sockets.connectedUsers()).thenReturn(Set.of(7L));
            when(mapper.revisions(anyList())).thenThrow(
                new IllegalStateException(canary, new java.sql.SQLException(canary))
            );
            poller.scan(new HashMap<>(), () -> true);
            poller.scan(new HashMap<>(), () -> true);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("SQLException").doesNotContain(canary);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
