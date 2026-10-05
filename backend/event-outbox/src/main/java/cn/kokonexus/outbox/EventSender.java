package cn.kokonexus.outbox;

/** 平台公共契约：EventSender 领域类型；字段单位、状态及可空性见各属性说明。 */
public interface EventSender {
    void send(NotificationEvent event) throws Exception;
}
