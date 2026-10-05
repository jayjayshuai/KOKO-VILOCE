package cn.kokonexus.notification.infrastructure;

import cn.kokonexus.notification.application.LiveFanoutService;
import cn.kokonexus.notification.application.NotificationApplicationService;
import cn.kokonexus.notification.domain.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** notification-service：RocketMqNotificationConsumer 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
@ConditionalOnProperty(prefix = "koko.notification.consumer", name = "enabled", havingValue = "true")
public class RocketMqNotificationConsumer {

    /** 本类诊断日志，禁止输出密码、令牌和业务正文。 */
    private static final Logger log = LoggerFactory.getLogger(RocketMqNotificationConsumer.class);
    /** NotificationApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final NotificationApplicationService service;
    /** LiveFanoutService 业务用例依赖，事务由 Spring 代理管理。 */
    private final LiveFanoutService fanoutService;
    /** ObjectMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final ObjectMapper objectMapper;
    /** RocketMQ Nameserver 地址。 */
    private final String nameServer;
    /** 主题或消息队列 Topic，具体见所属类型。 */
    private final String topic;
    /** 消费者组名称。 */
    private final String group;
    /** 消费者失败计数指标。 */
    private final Counter consumeFailures;
    /** DefaultMQPushConsumer 外部或领域适配器，失败不伪装为业务成功。 */
    private DefaultMQPushConsumer consumer;

    public RocketMqNotificationConsumer(
        NotificationApplicationService service,
        LiveFanoutService fanoutService,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        @Value("${koko.notification.consumer.nameserver}") String nameServer,
        @Value("${koko.notification.consumer.topic}") String topic,
        @Value("${koko.notification.consumer.group}") String group
    ) {
        this.service = service;
        this.fanoutService = fanoutService;
        this.objectMapper = objectMapper;
        this.nameServer = nameServer;
        this.topic = topic;
        this.group = group;
        this.consumeFailures = Counter.builder("koko.notification.consume.failures")
            .description("Notification message handling failures before broker redelivery")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 10000)
    public synchronized void ensureStarted() {
        if (consumer != null) return;
        DefaultMQPushConsumer candidate = new DefaultMQPushConsumer(group);
        candidate.setNamesrvAddr(nameServer);
        candidate.setConsumeThreadMin(1);
        candidate.setConsumeThreadMax(2);
        candidate.setMaxReconsumeTimes(8);
        try {
            candidate.subscribe(topic, "*");
            candidate.registerMessageListener(
                (org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) (messages, ignored) -> {
                    for (var message : messages) {
                        try {
                            NotificationEvent event = objectMapper.readValue(
                                new String(message.getBody(), StandardCharsets.UTF_8),
                                NotificationEvent.class
                            );
                            if ("LIVE_STARTED".equals(event.eventType())) fanoutService.accept(event);
                            else service.deliver(event);
                        } catch (Exception exception) {
                            consumeFailures.increment();
                            log.warn("Notification consume failed for message {}", message.getMsgId(), exception);
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
            );
            candidate.start();
            consumer = candidate;
            log.info("Notification consumer started");
        } catch (Exception exception) {
            candidate.shutdown();
            log.warn("Notification consumer startup failed; retry scheduled", exception);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (consumer != null) {
            consumer.shutdown();
            consumer = null;
        }
    }
}
