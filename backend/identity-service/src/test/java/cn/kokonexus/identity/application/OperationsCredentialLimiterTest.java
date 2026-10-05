package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsRateLimitedException;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import org.junit.jupiter.api.Test;

class OperationsCredentialLimiterTest {

    /** SQL 结果分支，不替代独立事务的集成验收。 */
    private final OperationsAuthorityMapper mapper = mock(OperationsAuthorityMapper.class);
    /** 显式被调用的独立服务。 */
    private final OperationsCredentialLimiter limiter = new OperationsCredentialLimiter(mapper);

    @Test
    void inactiveUserCannotSpendBudget() {
        when(mapper.lockActiveUser(10)).thenReturn(null);
        assertThatThrownBy(() -> limiter.consume(10)).isInstanceOf(OperationsAccessDeniedException.class);
        verify(mapper, never()).consumeAttempt(10);
    }

    @Test
    void exhaustedWindowCannotIncrementPastConstraint() {
        when(mapper.lockActiveUser(10)).thenReturn(10L);
        when(mapper.recentLimitReached(10)).thenReturn(1);
        assertThatThrownBy(() -> limiter.consume(10)).isInstanceOf(OperationsRateLimitedException.class);
        verify(mapper, never()).consumeAttempt(10);
    }

    @Test
    void insertAndMysqlUpsertAffectedRowsBothCountAsPersisted() {
        when(mapper.lockActiveUser(10)).thenReturn(10L);
        when(mapper.consumeAttempt(10)).thenReturn(1, 2, 0);
        limiter.consume(10);
        limiter.consume(10);
        assertThatThrownBy(() -> limiter.consume(10)).isInstanceOf(IllegalStateException.class);
    }
}
