package cn.kokonexus.outbox.persistence;

import cn.kokonexus.outbox.operations.OutboxReplayAudit;
import cn.kokonexus.outbox.operations.OutboxReplayState;
import org.apache.ibatis.annotations.Param;

/** 重放需行锁、条件更新和审计插入，复杂 SQL 在 XML，不允许任意表名或载荷修改。 */
public interface OutboxReplayMapper {
    /** 先按事件锁定，串行化同一事件的确认/重放。 */
    OutboxReplayState lockEvent(@Param("eventId") String eventId);

    /** READ_COMMITTED 事务中读取受理结果，避免旧 RR 快照漏掉已提交幂等请求。 */
    OutboxReplayAudit findAudit(@Param("requestId") String requestId);

    /** 只在 DEAD/代次/无租约都匹配时重排队；累计次数不清零。 */
    int requeue(@Param("eventId") String eventId, @Param("expectedGeneration") long expectedGeneration);

    /** 追加一次受理事实，失败必须回滚同事务中的事件更新。 */
    int insertAudit(OutboxReplayAudit audit);
}
