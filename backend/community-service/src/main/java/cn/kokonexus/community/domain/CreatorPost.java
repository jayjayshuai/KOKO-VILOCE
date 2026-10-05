package cn.kokonexus.community.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** community-service：CreatorPost 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("creator_post")
public class CreatorPost {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 资源所有者用户 ID。 */
    private Long ownerId;
    /** 公开访问路径标识。 */
    private String slug;
    /** 业务标题。 */
    private String title;
    /** 内容摘要。 */
    private String excerpt;
    /** 纯文本正文，不解释 HTML。 */
    private String body;
    /** 公开封面地址。 */
    private String coverUrl;
    /** 经归属验证的封面资产 UUID。 */
    private String coverAssetId;
    /** 领域资源类型。 */
    private String kind;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 乐观锁版本，修改必须携带当前值。 */
    private Long version;
    /** 点赞总数。 */
    private Long likeCount;
    /** 评论总数。 */
    private Long commentCount;
    /** 收藏总数。 */
    private Long favoriteCount;
    /** 正式发布时间；未发布为空，Asia/Shanghai。 */
    private LocalDateTime publishedAt;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 服务端最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
