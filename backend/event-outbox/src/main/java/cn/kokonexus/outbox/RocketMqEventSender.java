package cn.kokonexus.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

/** 平台公共契约：RocketMqEventSender 领域类型；字段单位、状态及可空性见各属性说明。 */
public class RocketMqEventSender implements EventSender, AutoCloseable {

    /** RocketMQ Nameserver 地址。 */
    private final String nameServer;
    /** 主题或消息队列 Topic，具体见所属类型。 */
    private final String topic;
    /** 生产者组名称。 */
    private final String producerGroup;
    /** ObjectMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final ObjectMapper objectMapper;
    /** DefaultMQProducer 外部或领域适配器，失败不伪装为业务成功。 */
    private DefaultMQProducer producer;

    public RocketMqEventSender(String nameServer, String topic, String producerGroup, ObjectMapper objectMapper) {
        this.nameServer = nameServer;
        this.topic = topic;
        this.producerGroup = producerGroup;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void send(NotificationEvent event) throws Exception {
        if (producer == null) {
            DefaultMQProducer candidate = new DefaultMQProducer(producerGroup);
            candidate.setNamesrvAddr(nameServer);
            candidate.setSendMsgTimeout(3000);
            candidate.setRetryTimesWhenSendFailed(0);
            try {
                candidate.start();
                producer = candidate;
            } catch (Exception exception) {
                candidate.shutdown();
                throw exception;
            }
        }
        Message message = new Message(
            topic,
            event.eventType(),
            event.eventId(),
            objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)
        );
        if (producer.send(message).getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Broker 未确认事件持久化");
        }
    }

    @Override
    public synchronized void close() {
        if (producer != null) {
            producer.shutdown();
            producer = null;
        }
    }
}
