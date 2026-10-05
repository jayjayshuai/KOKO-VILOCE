package cn.kokonexus.community.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** community-service：Community 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("community")
public class Community {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 资源所有者用户 ID。 */
    private Long ownerId;
    /** 公开访问路径标识。 */
    private String slug;
    /** 业务名称。 */
    private String name;
    /** 业务说明。 */
    private String description;
    /** 社区短徽标。 */
    private String badge;
    /** 成员人数。 */
    private Long memberCount;
    /** 资源可见范围，PUBLIC 或 PRIVATE。 */
    private String visibility;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 乐观锁版本，修改必须携带当前值。 */
    private Long version;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 服务端最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
