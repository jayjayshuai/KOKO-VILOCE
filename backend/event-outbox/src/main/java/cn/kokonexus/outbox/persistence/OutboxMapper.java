package cn.kokonexus.outbox.persistence;

import cn.kokonexus.outbox.OutboxRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 平台公共契约：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface OutboxMapper {
    int insert(OutboxRecord record);
    /** 已过期且本代十次耗尽的崩溃/确认未知任务转DEAD，不重复领取超预算任务。 */
    int exhaustExpired();
    int claim(@Param("token") String token, @Param("batchSize") int batchSize);
    List<OutboxRecord> selectClaimed(@Param("token") String token);
    int markSent(@Param("id") String id, @Param("token") String token);
    int markFailed(
        @Param("id") String id,
        @Param("token") String token,
        @Param("dead") boolean dead,
        @Param("backoffSeconds") int backoffSeconds,
        @Param("error") String error
    );
    long countOutstanding();
    long countDead();
    long oldestOutstandingAgeSeconds();
}
