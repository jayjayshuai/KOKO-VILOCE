package cn.kokonexus.chat.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.chat.persistence.ChatSyncMapper;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 写范围、顺序和 REQUIRED/MANDATORY 代理边界；不冒充真实 SQL 验证。 */
class ChatSyncWriterTest {

    private final ChatSyncMapper mapper = mock(ChatSyncMapper.class);
    private final ChatSyncWriter writer = new ChatSyncWriter(mapper);

    @Test
    void locksDistinctUsersInAscendingOrderAndAcceptsUpsertCounts() {
        when(mapper.advance(7)).thenReturn(1);
        when(mapper.advance(42)).thenReturn(2);
        writer.changed(List.of(42L, 7L, 42L));
        var order = inOrder(mapper);
        order.verify(mapper).advance(7);
        order.verify(mapper).advance(42);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidOrOversizedRecipientsNeverWrite() {
        for (var users : Arrays.asList(
            null,
            List.<Long>of(),
            Arrays.asList(1L, null),
            List.of(0L),
            LongStream.rangeClosed(1, 51).boxed().toList()
        )) {
            assertThatThrownBy(() -> writer.changed(users)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void unknownAffectedRowsStopRegistration() {
        when(mapper.advance(7)).thenReturn(0);
        assertThatThrownBy(() -> writer.changed(List.of(7L, 42L))).isInstanceOf(IllegalStateException.class);
        verify(mapper).advance(7);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void mandatoryProxyRejectsCallOutsideBusinessTransaction() {
        var manager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() throws TransactionException {
                return new Object();
            }

            @Override
            protected void doBegin(Object tx, TransactionDefinition definition) {
                fail("must not begin");
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                fail("must not commit");
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                fail("must not roll back");
            }
        };
        var factory = new ProxyFactory(writer);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        assertThatThrownBy(() -> ((ChatSyncWriter) factory.getProxy()).changed(List.of(7L))).isInstanceOf(
            org.springframework.transaction.IllegalTransactionStateException.class
        );
        verifyNoInteractions(mapper);
    }
}
