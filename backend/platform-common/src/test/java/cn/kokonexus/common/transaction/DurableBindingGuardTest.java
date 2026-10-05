package cn.kokonexus.common.transaction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 使用真实 Spring 事务代理/回调；测试事务管理器不代替 JDBC 或 MySQL 的提交证据。 */
class DurableBindingGuardTest {

    @Test
    void durableStagePrecedesBusinessCommitAndRelease() {
        withContext((writer, manager) -> {
            writer.staged(false);
            assertEquals(List.of("begin", "acquire", "stage", "write", "commit", "release"), manager.events);
        });
    }

    @Test
    void failedDurableStageRollsBackAndRunsKnownRollbackRelease() {
        withContext((writer, manager) -> {
            assertThrows(IllegalStateException.class, () -> writer.staged(true));
            assertEquals(List.of("begin", "acquire", "stage", "rollback", "release"), manager.events);
        });
    }

    @Test
    void unknownCommitKeepsImmediateProtectionEvenWhenDurableStageWasInvoked() {
        withContext((writer, manager) -> {
            manager.failCommit = true;
            assertThrows(TransactionSystemException.class, () -> writer.staged(false));
            assertEquals(List.of("begin", "acquire", "stage", "write", "commit"), manager.events);
        });
    }

    @Test
    void proxyCommitReleasesOnlyAfterCommit() {
        withContext((writer, manager) -> {
            writer.write(false, false);
            assertEquals(List.of("begin", "acquire", "write", "commit", "release"), manager.events);
        });
    }

    @Test
    void rollbackReleasesOnlyAfterRollbackAndPreservesBusinessFailure() {
        withContext((writer, manager) -> {
            assertThrows(IllegalArgumentException.class, () -> writer.write(true, false));
            assertEquals(List.of("begin", "acquire", "write", "rollback", "release"), manager.events);
        });
    }

    @Test
    void unknownCommitNeverReleases() {
        withContext((writer, manager) -> {
            manager.failCommit = true;
            assertThrows(TransactionSystemException.class, () -> writer.write(false, false));
            assertEquals(List.of("begin", "acquire", "write", "commit"), manager.events);
        });
    }

    @Test
    void releaseFailureDoesNotTurnAnAlreadyCommittedWriteIntoFailure() {
        withContext((writer, manager) -> {
            writer.write(false, true);
            assertEquals(List.of("begin", "acquire", "write", "commit", "release"), manager.events);
        });
    }

    @Test
    void absentReadOnlyOrSelfInvokedTransactionNeverAcquires() {
        withContext((writer, manager) -> {
            assertThrows(IllegalStateException.class, writer::withoutProxy);
            assertTrue(manager.events.isEmpty());
            assertThrows(IllegalStateException.class, writer::readOnly);
            assertEquals(List.of("begin", "rollback"), manager.events);
        });
    }

    @Test
    void failedOrRejectedAcquireDoesNotReleaseUnknownRemoteState() {
        withContext((writer, manager) -> {
            assertThrows(IllegalStateException.class, () -> writer.acquireFailure(true));
            assertEquals(List.of("begin", "acquire", "rollback"), manager.events);
            manager.events.clear();
            assertThrows(IllegalArgumentException.class, () -> writer.acquireFailure(false));
            assertEquals(List.of("begin", "acquire", "rollback"), manager.events);
        });
    }

    private void withContext(java.util.function.BiConsumer<Writer, TestTransactions> test) {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            test.accept(context.getBean(Writer.class), context.getBean(TestTransactions.class));
        }
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {

        @Bean
        TestTransactions transactionManager() {
            return new TestTransactions();
        }

        @Bean
        Writer writer(TestTransactions manager) {
            return new Writer(manager);
        }
    }

    static class Writer {

        private final TestTransactions manager;

        Writer(TestTransactions manager) {
            this.manager = manager;
        }

        @Transactional
        public void staged(boolean failStage) {
            DurableBindingGuard.acquire(
                "test-correlation",
                () -> {
                    manager.events.add("acquire");
                    return true;
                },
                () -> {
                    manager.events.add("stage");
                    if (failStage) throw new IllegalStateException("simulated SQL stage failure");
                },
                () -> manager.events.add("release")
            );
            manager.events.add("write");
        }

        @Transactional
        public void write(boolean failWrite, boolean failRelease) {
            DurableBindingGuard.acquire(
                "test-correlation",
                () -> {
                    manager.events.add("acquire");
                    return true;
                },
                () -> {
                    manager.events.add("release");
                    if (failRelease) throw new IllegalStateException("simulated remote release unavailable");
                }
            );
            manager.events.add("write");
            if (failWrite) throw new IllegalArgumentException("simulated business failure");
        }

        public void withoutProxy() {
            write(false, false);
        }

        @Transactional(readOnly = true)
        public void readOnly() {
            write(false, false);
        }

        @Transactional
        public void acquireFailure(boolean throwsUnknown) {
            DurableBindingGuard.acquire(
                "test-correlation",
                () -> {
                    manager.events.add("acquire");
                    if (throwsUnknown) throw new IllegalStateException("simulated response lost");
                    return false;
                },
                () -> manager.events.add("release")
            );
        }
    }

    static class TestTransactions extends AbstractPlatformTransactionManager {

        private final List<String> events = new ArrayList<>();
        private boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
            if (failCommit) throw new TransactionSystemException("simulated unknown commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
