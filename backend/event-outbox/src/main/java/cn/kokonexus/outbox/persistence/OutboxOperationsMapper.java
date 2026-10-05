package cn.kokonexus.outbox.persistence;

import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.outbox.operations.OutboxOperationsEvent;
import cn.kokonexus.outbox.operations.OutboxReplayAudit;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 固定本库表及复合游标绑定，不接受客户端排序、表名、SQL 或无限 offset。 */
public interface OutboxOperationsMapper {
    List<OutboxOperationsEvent> dead(@Param("cursor") OutboxEventCursor cursor, @Param("limit") int limit);
    OutboxOperationsEvent detail(@Param("eventId") String eventId);
    OutboxReplayAudit receipt(@Param("eventId") String eventId, @Param("requestId") String requestId);
    List<OutboxReplayAudit> audits(
        @Param("eventId") String eventId,
        @Param("beforeGeneration") Long beforeGeneration,
        @Param("limit") int limit
    );
}
