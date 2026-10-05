package cn.kokonexus.identity.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 角色关系持久快照，撤销保留版本，不对客户端开放实体更新。 */
@Getter
@Setter
public class OperationsRoleAssignment {

    /** 账号 ID。 */
    private Long userId;
    /** 固定角色代码。 */
    private String roleCode;
    /** ACTIVE/REVOKED。 */
    private String status;
    /** 关系单调版本。 */
    private Long version;
    /** 可空到期时间，Asia/Shanghai。 */
    private LocalDateTime expiresAt;
}
