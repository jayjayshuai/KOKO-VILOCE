package cn.kokonexus.identity.application;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsRateLimitedException;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 不同 JVM 共享每账号五次/一分钟预算；密码错误不能回滚已提交的尝试。 */
@Service
@RequiredArgsConstructor
public class OperationsCredentialLimiter {

    /** 同库限速持久化与账号行锁。 */
    private final OperationsAuthorityMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(long userId) {
        if (mapper.lockActiveUser(userId) == null) throw new OperationsAccessDeniedException("账号不可用");
        if (mapper.recentLimitReached(userId) != 0) throw new OperationsRateLimitedException();
        if (mapper.consumeAttempt(userId) <= 0) throw new IllegalStateException("二次确认限速未持久化");
    }
}
