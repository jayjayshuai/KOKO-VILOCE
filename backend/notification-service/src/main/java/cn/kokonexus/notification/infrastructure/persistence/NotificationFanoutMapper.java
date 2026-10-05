package cn.kokonexus.notification.infrastructure.persistence;

import cn.kokonexus.notification.domain.FanoutJob;
import cn.kokonexus.notification.domain.NotificationEvent;
import org.apache.ibatis.annotations.Param;

/** notification-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface NotificationFanoutMapper {
    int insertIdempotent(NotificationEvent event);
    int claim(@Param("token") String token);
    FanoutJob selectClaimed(@Param("token") String token);
    int advance(
        @Param("eventId") String eventId,
        @Param("token") String token,
        @Param("cursor") long cursor,
        @Param("done") boolean done
    );
    int fail(
        @Param("eventId") String eventId,
        @Param("token") String token,
        @Param("dead") boolean dead,
        @Param("backoffSeconds") int backoffSeconds,
        @Param("error") String error
    );
    long countOutstanding();
    long countDead();
    long oldestOutstandingAgeSeconds();
}
