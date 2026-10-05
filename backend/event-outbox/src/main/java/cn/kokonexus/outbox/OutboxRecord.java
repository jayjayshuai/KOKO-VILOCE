package cn.kokonexus.outbox;

import java.time.LocalDateTime;

/** 平台公共契约：OutboxRecord 领域类型；字段单位、状态及可空性见各属性说明。 */
public class OutboxRecord {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    private String id;
    /** 通知事件类型。 */
    private String eventType;
    /** 通知接收用户 ID。 */
    private Long recipientId;
    /** 触发事件的用户 ID。 */
    private Long actorId;
    /** 事件关联的业务资源 ID。 */
    private String resourceId;
    /** 通知摘要。 */
    private String summary;
    /** 当前投递轮次的尝试次数；人工重放重置预算，生命周期累计数由 total_attempts 独立保留。 */
    private Integer attempts;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public NotificationEvent toEvent() {
        return new NotificationEvent(id, recipientId, actorId, eventType, resourceId, summary);
    }
}
