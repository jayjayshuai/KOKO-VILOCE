package cn.kokonexus.voice.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** voice-service：VoiceRoom 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("voice_room")
public class VoiceRoom {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 资源所有者用户 ID。 */
    private Long ownerId;
    /** 所有者名称快照。 */
    private String ownerName;
    /** 公开访问路径标识。 */
    private String slug;
    /** 业务标题。 */
    private String title;
    /** 主题或消息队列 Topic，具体见所属类型。 */
    private String topic;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 供应商房间名称。 */
    private String providerRoomName;
    /** 房间人数上限。 */
    private Integer maxParticipants;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 关闭时间；未关闭为空，Asia/Shanghai。 */
    private LocalDateTime closedAt;
}
