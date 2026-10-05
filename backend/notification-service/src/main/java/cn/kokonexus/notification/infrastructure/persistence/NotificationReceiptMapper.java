package cn.kokonexus.notification.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;

/** notification-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface NotificationReceiptMapper {
    int claim(@Param("eventId") String eventId, @Param("recipientId") long recipientId);
    int setDisposition(
        @Param("eventId") String eventId,
        @Param("recipientId") long recipientId,
        @Param("disposition") String disposition
    );
}
