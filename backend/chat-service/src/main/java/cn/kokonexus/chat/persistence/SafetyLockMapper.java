package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatReport;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** 关键并发 SQL 独立于 CRUD，必须在本地事务内调用。 */
public interface SafetyLockMapper {
    /** 锁用户级配额；upsert 无论首次或已存在都持有行锁至事务结束。 */
    int lockActor(@Param("userId") long userId);
    /** 用户对按 ID 排序，双方拉黑/联系授权持有同一行锁。 */
    int lockPair(@Param("lowId") long lowId, @Param("highId") long highId);
    /** 当前读而不是 RR 快照读，防等待拉黑提交后仍看到旧权限。 */
    Integer blockedPair(@Param("lowId") long lowId, @Param("highId") long highId);
    /** 用户对上的方向标志与本人拉黑列表在同一事务更新。 */
    int setBlocking(
        @Param("lowId") long lowId,
        @Param("highId") long highId,
        @Param("ownerId") long ownerId,
        @Param("blocked") boolean blocked
    );
    /** 锁定待审核事实，串行化最终决定和审计。 */
    ChatReport lockReport(@Param("id") String id);
    /** 只允许版本匹配的 PENDING 记录进入最终状态，必须检查更新行数。 */
    int review(
        @Param("id") String id,
        @Param("version") long version,
        @Param("decision") String decision,
        @Param("note") String note,
        @Param("reviewedAt") LocalDateTime reviewedAt
    );
}
