package cn.kokonexus.notification.infrastructure.persistence;

import cn.kokonexus.notification.domain.NotificationInbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** notification-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface NotificationInboxMapper extends BaseMapper<NotificationInbox> {
    int insertIdempotent(NotificationInbox notification);
    List<NotificationInbox> selectPageForUser(
        @Param("userId") long userId,
        @Param("offset") long offset,
        @Param("size") int size
    );
    long countForUser(@Param("userId") long userId);
    int markRead(@Param("userId") long userId, @Param("notificationId") long notificationId);
    boolean existsForUser(@Param("userId") long userId, @Param("notificationId") long notificationId);
}
