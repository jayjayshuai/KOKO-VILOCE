package cn.kokonexus.notification.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** notification-service：NotificationInbox 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("notification_inbox")
public class NotificationInbox {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId
    private Long id;

    /** 事件幂等 ID。 */
    private String eventId;
    /** 通知接收用户 ID。 */
    private Long recipientId;
    /** 触发事件的用户 ID。 */
    private Long actorId;
    /** 通知事件类型。 */
    private String eventType;
    /** 事件关联的业务资源 ID。 */
    private String resourceId;
    /** 通知摘要。 */
    private String summary;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 已读时间；未读为空，Asia/Shanghai。 */
    private LocalDateTime readAt;
}
