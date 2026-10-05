package cn.kokonexus.chat.transport;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 固定环回/端口的隔离真实Redis客户端；不接受生产或任意地址。 */
public final class ChatQuotaRedisFixture implements AutoCloseable {

    /** 本入口独占客户端，退出关闭。 */
    private final LettuceConnectionFactory factory;
    /** 真Redis String序列化，不使用内存模拟。 */
    public final StringRedisTemplate redis;

    public ChatQuotaRedisFixture() {
        if (
            !"127.0.0.1".equals(System.getenv("CHAT_QUOTA_REDIS_HOST")) ||
            !"26489".equals(System.getenv("CHAT_QUOTA_REDIS_PORT"))
        ) throw new IllegalArgumentException("固定环回Redis验收目标必需");
        String password = System.getenv("CHAT_QUOTA_REDIS_PASSWORD");
        if (password == null || password.length() < 32) throw new IllegalArgumentException("隔离Redis随机凭据必需");
        var config = new RedisStandaloneConfiguration("127.0.0.1", 26489);
        config.setPassword(password);
        factory = new LettuceConnectionFactory(
            config,
            LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofMillis(200))
                .build()
        );
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @Override
    public void close() {
        factory.destroy();
    }
}
