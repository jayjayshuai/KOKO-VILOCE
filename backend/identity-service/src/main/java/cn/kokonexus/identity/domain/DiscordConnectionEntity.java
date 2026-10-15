package cn.kokonexus.identity.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内部绑定行；对外只映射最小投影，旧记录未验证不自动标记成功。 */
@Getter
@Setter
@TableName("identity_discord_connection")
public class DiscordConnectionEntity {

    /** 平台账号，一人最多一个绑定。 */
    @TableId
    private Long userId;

    /** Discord标识，数据库唯一约束防跨账号复用。 */
    private String discordUserId;

    /** 仅服务端可解密的刷新令牌，不序列化或记录日志。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String encryptedRefreshToken;

    /** 旧记录为历史时区，新绑定为明确UTC；对外须区分状态。 */
    private LocalDateTime connectedAt;
    /** Discord公开用户名，不是登录账号。 */
    private String discordUsername;
    /** 新确认绑定ACTIVE，历史记录默认UNVERIFIED。 */
    private String connectionStatus;
    /** 此绑定采用的单调授权轮次，解绑/恢复时必须比较。 */
    private Long connectionVersion;
}
