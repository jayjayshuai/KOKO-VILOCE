package cn.kokonexus.chat.transport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 客户端参数/未知结果与指标；Redis原子性、自然TTL及跨节点另用真Redis检查。 */
class RedisChatConnectionQuotaTest {

    /** 明确的I/O桩，不替换生产脚本源文件。 */
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    /** 无外部推送的局部指标。 */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** 生产适配器。 */
    private final RedisChatConnectionQuota quota = new RedisChatConnectionQuota(redis, meters);

    @Test
    void inputValidationNeverCreatesArbitraryKeys() {
        for (String value : new String[] { null, "", "1-1-1-1-1", "not-a-uuid" }) {
            assertThatThrownBy(() -> quota.acquire(1, value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> quota.renew(0, UUID.randomUUID().toString())).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(redis);
    }

    @Test
    void denyAndExpiryAreNotDependencyFailures() {
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(quota.acquire(7, UUID.randomUUID().toString())).isFalse();
        assertThat(quota.renew(7, UUID.randomUUID().toString())).isFalse();
        quota.release(7, UUID.randomUUID().toString());
        assertThat(meters.get("koko.chat.quota.denied").counter().count()).isEqualTo(1);
        assertThat(meters.get("koko.chat.quota.expired").counter().count()).isEqualTo(1);
        assertThat(meters.find("koko.chat.quota.failures").counter()).isNull();
    }

    @Test
    void unknownResultAndRedisFailureCannotBecomeAdmissionSuccess() {
        for (Long response : new Long[] { null, -2L, 2L }) {
            doReturn(response).when(redis).execute(any(), anyList(), any(Object[].class));
            assertThatThrownBy(() -> quota.acquire(7, UUID.randomUUID().toString())).isInstanceOf(
                ChatQuotaUnavailableException.class
            );
        }
        doThrow(new IllegalStateException("synthetic-sensitive-redis-error"))
            .when(redis)
            .execute(any(), anyList(), any(Object[].class));
        assertThatThrownBy(() -> quota.renew(7, UUID.randomUUID().toString()))
            .isInstanceOf(ChatQuotaUnavailableException.class)
            .hasMessage("共享聊天配额暂不可用");
        assertThat(meters.get("koko.chat.quota.failures").tag("operation", "acquire").counter().count()).isEqualTo(3);
        assertThat(meters.get("koko.chat.quota.failures").tag("operation", "renew").counter().count()).isEqualTo(1);
    }

    @Test
    void everyScriptUsesOneHashtaggedUserKeyAndBoundedArguments() {
        String connection = UUID.randomUUID().toString();
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
        assertThat(quota.acquire(7, connection)).isTrue();
        assertThat(quota.renew(7, connection)).isTrue();
        quota.release(7, connection);
        verify(redis, times(3)).execute(
            any(),
            eq(java.util.List.of("koko:chat:connections:v1:{7}")),
            eq(new Object[] { connection, "120000", "3" })
        );
    }
}
