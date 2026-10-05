package cn.kokonexus.notification.domain;

/** notification-service：FanoutJob 领域类型；字段单位、状态及可空性见各属性说明。 */
public class FanoutJob {

    /** 事件幂等 ID。 */
    private String eventId;
    /** 触发事件的用户 ID。 */
    private Long actorId;
    /** 事件关联的业务资源 ID。 */
    private String resourceId;
    /** 通知摘要。 */
    private String summary;
    /** 关注者扇出游标，单调前进。 */
    private Long followerCursor;
    /** 已尝试执行次数。 */
    private Integer attempts;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
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

    public Long getFollowerCursor() {
        return followerCursor;
    }

    public void setFollowerCursor(Long followerCursor) {
        this.followerCursor = followerCursor;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }
}
