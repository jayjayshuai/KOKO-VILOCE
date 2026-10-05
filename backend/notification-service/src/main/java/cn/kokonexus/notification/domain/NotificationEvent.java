package cn.kokonexus.notification.domain;

import java.util.UUID;

/** notification-service：NotificationEvent 领域类型；字段单位、状态及可空性见各属性说明。 */
public record NotificationEvent(
    @io.swagger.v3.oas.annotations.media.Schema(description = "事件幂等 ID") String eventId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "通知接收用户 ID") long recipientId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "触发事件的用户 ID") long actorId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "通知事件类型") String eventType,
    @io.swagger.v3.oas.annotations.media.Schema(description = "事件关联的业务资源 ID") String resourceId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "通知摘要") String summary
) {
    public void validate() {
        try {
            UUID.fromString(eventId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("事件标识无效", exception);
        }
        if (recipientId <= 0 || actorId <= 0 || recipientId == actorId) {
            throw new IllegalArgumentException("通知参与者无效");
        }
        if (!("FOLLOW".equals(eventType) || "COMMENT".equals(eventType) || "LIVE_STARTED".equals(eventType))) {
            throw new IllegalArgumentException("通知类型无效");
        }
        if (resourceId == null || resourceId.isBlank() || resourceId.length() > 80) {
            throw new IllegalArgumentException("通知资源无效");
        }
        if (summary == null || summary.isBlank() || summary.length() > 500) {
            throw new IllegalArgumentException("通知摘要无效");
        }
    }
}
