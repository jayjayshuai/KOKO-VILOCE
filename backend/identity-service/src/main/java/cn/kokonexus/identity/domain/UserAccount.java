package cn.kokonexus.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** identity-service：UserAccount 领域类型；字段单位、状态及可空性见各属性说明。 */
@lombok.Getter
@lombok.Setter
@TableName("identity_user")
public class UserAccount {

    /** 唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录邮箱，个人敏感信息。 */
    private String email;
    /** BCrypt 密码摘要，禁止外部序列化与日志输出。 */
    private String passwordHash;
    /** 公开用户名，用于精确查询。 */
    private String handle;
    /** 用户公开显示名称。 */
    private String displayName;
    /** 公开头像地址。 */
    private String avatarUrl;
    /** 业务状态，允许值以所属领域状态机为准。 */
    private String status;
    /** 运营角色授权单调版本；角色修改递增，用于撤销旧确认凭据。 */
    private Long operationsVersion;
    /** 服务端创建时间，数据库时区 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 服务端最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
