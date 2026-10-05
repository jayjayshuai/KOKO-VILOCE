package cn.kokonexus.outbox;

/** 平台公共契约：NotificationEvent 领域类型；字段单位、状态及可空性见各属性说明。 */
public record NotificationEvent(
    @io.swagger.v3.oas.annotations.media.Schema(description = "事件幂等 ID") String eventId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "通知接收用户 ID") long recipientId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "触发事件的用户 ID") long actorId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "通知事件类型") String eventType,
    @io.swagger.v3.oas.annotations.media.Schema(description = "事件关联的业务资源 ID") String resourceId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "通知摘要") String summary
) {}
