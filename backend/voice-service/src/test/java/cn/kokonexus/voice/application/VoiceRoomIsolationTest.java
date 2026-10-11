package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 注解约束防止回退到不同房间互等的RR空范围锁；实际事务行为另由MySQL屏障验证。 */
class VoiceRoomIsolationTest {

    @Test
    void roomMutexTransactionsKeepExplicitReadCommittedAndBoundedTimeout() {
        int count = 0;
        for (Class<?> type : new Class[] { VoiceInteractionService.class, VoiceClosureState.class })
            for (var method : type.getDeclaredMethods()) {
                var transaction = method.getAnnotation(Transactional.class);
                if (transaction == null) continue;
                count++;
                assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
                assertThat(transaction.timeout()).isBetween(1, 3);
            }
        assertThat(count).isGreaterThan(2);
    }
}
