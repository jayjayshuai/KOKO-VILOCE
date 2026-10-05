package cn.kokonexus.community.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** community-service：CreatorPostComment 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("creator_post_comment")
public class CreatorPostComment {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文章 ID。 */
    private Long postId;
    /** 操作用户 ID。 */
    private Long userId;
    /** 纯文本正文，不解释 HTML。 */
    private String body;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 乐观锁版本，修改必须携带当前值。 */
    private Long version;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 服务端最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
