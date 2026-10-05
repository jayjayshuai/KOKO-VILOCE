package cn.kokonexus.notification.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;

/** notification-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface NotificationPreferenceMapper {
    Boolean enabled(@Param("userId") long userId, @Param("eventType") String eventType);
    int save(@Param("userId") long userId, @Param("eventType") String eventType, @Param("enabled") boolean enabled);
}
