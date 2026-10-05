package cn.kokonexus.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** identity-service：CreatorProfileEntity 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("creator_profile")
public class CreatorProfileEntity {

    /** 操作用户 ID。 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 公开访问路径标识。 */
    private String slug;
    /** 用户公开显示名称。 */
    private String displayName;
    /** 创作者主页短介绍。 */
    private String headline;
    /** 创作者个人简介。 */
    private String bio;
    /** 公开头像地址。 */
    private String avatarUrl;
    /** 经归属验证的头像资产 UUID。 */
    private String avatarAssetId;
    /** 公开横幅地址。 */
    private String bannerUrl;
    /** 经归属验证的横幅资产 UUID。 */
    private String bannerAssetId;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 乐观锁版本，修改必须携带当前值。 */
    private Long version;
    /** 关注人数。 */
    private Long followerCount;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 服务端最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
