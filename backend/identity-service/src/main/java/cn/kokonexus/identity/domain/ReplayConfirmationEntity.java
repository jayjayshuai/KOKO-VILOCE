package cn.kokonexus.identity.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 秘密确认凭据持久快照，禁止 toString 输出；不直接作为外部响应。 */
@Getter
@Setter
public class ReplayConfirmationEntity {

    /** 原随机凭据的 SHA256。 */
    private String tokenHash;
    /** 所属账号。 */
    private Long userId;
    /** 真实认证会话的摘要。 */
    private String sessionHash;
    /** 域与整条命令的摘要。 */
    private String commandHash;
    /** 确认时角色变更版本。 */
    private Long authorityVersion;
    /** 密码变更使旧确认失效，不保存明文密码。 */
    private String credentialFingerprint;
    /** 数据库时间到期点，Asia/Shanghai。 */
    private LocalDateTime expiresAt;
}
