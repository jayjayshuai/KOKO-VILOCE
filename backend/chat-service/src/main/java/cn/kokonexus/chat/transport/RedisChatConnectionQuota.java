package cn.kokonexus.chat.transport;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 每用户单键的三份有时限租约；脚本作为不可变资源发布，值参数全部绑定。 */
@Component
public final class RedisChatConnectionQuota implements ChatConnectionQuota {

    /** 全节点共享且带版本的内部键；账号数随真实认证连接增长，不保存令牌或正文。 */
    static final String KEY_PREFIX = "koko:chat:connections:v1:";
    /** 比75秒入站空闲保护保留余量，节点崩溃后占用有限；不是提交事务围栏。 */
    public static final long LEASE_MILLIS = 120_000;
    /** 全局有效连接租约上限，不允许运行期随节点配置不同而放大。 */
    private static final int LIMIT = 3;
    /** 共享事实存储；String序列化保持Lua键/参数可复核。 */
    private final StringRedisTemplate redis;
    /** 只按有限操作名计数，不使用用户/UUID标签。 */
    private final MeterRegistry meters;
    /** 单键申请脚本，NOSCRIPT按Spring Data标准处理，不动态生成源代码。 */
    private final DefaultRedisScript<Long> acquireScript = script("acquire");
    /** 不插入不存在或过期连接的续期脚本。 */
    private final DefaultRedisScript<Long> renewScript = script("renew");
    /** 只删本连接UUID的幂等释放脚本。 */
    private final DefaultRedisScript<Long> releaseScript = script("release");

    public RedisChatConnectionQuota(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    @Override
    public boolean acquire(long userId, String connectionId) {
        long result = execute("acquire", acquireScript, userId, connectionId);
        if (result == 0) meters.counter("koko.chat.quota.denied").increment();
        return result == 1;
    }

    @Override
    public boolean renew(long userId, String connectionId) {
        long result = execute("renew", renewScript, userId, connectionId);
        if (result == 0) meters.counter("koko.chat.quota.expired").increment();
        return result == 1;
    }

    @Override
    public void release(long userId, String connectionId) {
        execute("release", releaseScript, userId, connectionId);
    }

    private long execute(String operation, DefaultRedisScript<Long> script, long userId, String connectionId) {
        if (userId <= 0 || connectionId == null || !UUID.fromString(connectionId).toString().equals(connectionId)) {
            throw new IllegalArgumentException("连接租约身份/UUID无效");
        }
        try {
            Long result = redis.execute(
                script,
                List.of(KEY_PREFIX + "{" + userId + "}"),
                connectionId,
                Long.toString(LEASE_MILLIS),
                Integer.toString(LIMIT)
            );
            if (result == null || (result != 0 && result != 1)) throw new IllegalStateException("连接租约结果未知");
            return result;
        } catch (RuntimeException failure) {
            meters.counter("koko.chat.quota.failures", "operation", operation).increment();
            throw new ChatQuotaUnavailableException(failure);
        }
    }

    private static DefaultRedisScript<Long> script(String operation) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("redis/chat-quota-" + operation + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
