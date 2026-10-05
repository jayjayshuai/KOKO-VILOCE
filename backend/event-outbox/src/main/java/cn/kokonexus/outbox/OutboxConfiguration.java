package cn.kokonexus.outbox;

import cn.kokonexus.outbox.persistence.OutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 平台公共契约：服务配置；不在源码内保存生产密钥。 */
@Configuration
@EnableScheduling
public class OutboxConfiguration {

    @Bean
    public OutboxWriter outboxWriter(OutboxMapper mapper) {
        return new OutboxWriter(mapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "koko.outbox", name = "enabled", havingValue = "true")
    public RocketMqEventSender rocketMqEventSender(
        @Value("${koko.outbox.nameserver:localhost:9876}") String nameServer,
        @Value("${koko.outbox.topic:koko_nexus_notifications}") String topic,
        @Value("${spring.application.name}") String applicationName,
        ObjectMapper objectMapper
    ) {
        return new RocketMqEventSender(nameServer, topic, applicationName + "-outbox", objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "koko.outbox", name = "enabled", havingValue = "true")
    public OutboxRelay outboxRelay(OutboxMapper mapper, EventSender sender) {
        return new OutboxRelay(mapper, sender);
    }

    @Bean
    @ConditionalOnProperty(prefix = "koko.outbox", name = "enabled", havingValue = "true")
    public OutboxMetrics outboxMetrics(OutboxMapper mapper, MeterRegistry meterRegistry) {
        return new OutboxMetrics(mapper, meterRegistry);
    }
}
