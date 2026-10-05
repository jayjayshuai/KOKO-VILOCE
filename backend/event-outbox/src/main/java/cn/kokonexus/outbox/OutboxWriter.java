package cn.kokonexus.outbox;

import cn.kokonexus.outbox.persistence.OutboxMapper;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 平台公共契约：OutboxWriter 领域类型；字段单位、状态及可空性见各属性说明。 */
public class OutboxWriter {

    /** OutboxMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final OutboxMapper mapper;

    public OutboxWriter(OutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(long recipientId, long actorId, String eventType, String resourceId, String summary) {
        if (recipientId <= 0 || actorId <= 0 || recipientId == actorId) {
            throw new IllegalArgumentException("事件参与者无效");
        }
        if (!("FOLLOW".equals(eventType) || "COMMENT".equals(eventType) || "LIVE_STARTED".equals(eventType))) {
            throw new IllegalArgumentException("事件类型无效");
        }
        if (
            resourceId == null ||
            resourceId.isBlank() ||
            resourceId.length() > 80 ||
            summary == null ||
            summary.isBlank() ||
            summary.length() > 500
        ) {
            throw new IllegalArgumentException("事件内容无效");
        }
        OutboxRecord event = new OutboxRecord();
        event.setId(UUID.randomUUID().toString());
        event.setRecipientId(recipientId);
        event.setActorId(actorId);
        event.setEventType(eventType);
        event.setResourceId(resourceId);
        event.setSummary(summary);
        if (mapper.insert(event) != 1) throw new IllegalStateException("事件暂存失败");
        return event.getId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueueLiveStarted(long creatorId, long streamId, String title) {
        if (creatorId <= 0 || streamId <= 0 || title == null || title.isBlank()) {
            throw new IllegalArgumentException("直播事件无效");
        }
        OutboxRecord event = new OutboxRecord();
        event.setId(UUID.randomUUID().toString());
        event.setRecipientId(0L);
        event.setActorId(creatorId);
        event.setEventType("LIVE_STARTED");
        event.setResourceId(String.valueOf(streamId));
        String summary = "你关注的创作者开始直播：" + title.trim();
        event.setSummary(summary.length() > 500 ? summary.substring(0, 500) : summary);
        if (mapper.insert(event) != 1) throw new IllegalStateException("直播事件暂存失败");
        return event.getId();
    }
}
