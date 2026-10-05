package cn.kokonexus.identity.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 只追加角色变更审计，不提供修改/删除方法。 */
@Getter
@Setter
public class OperationsRoleAudit {

    /** 幂等受理 UUID。 */
    private String requestId;
    /** 当前认证操作者，服务器初始化时可空。 */
    private Long operatorId;
    /** OPERATOR/SERVER_BOOTSTRAP 来源。 */
    private String source;
    /** 目标账号。 */
    private Long userId;
    /** 固定角色。 */
    private String roleCode;
    /** 操作者确认版本。 */
    private Long expectedVersion;
    /** 已受理新版本。 */
    private Long acceptedVersion;
    /** 原关系状态，未赋权时 NONE。 */
    private String previousStatus;
    /** 本次受理状态。 */
    private String acceptedStatus;
    /** 原可空到期时间。 */
    private LocalDateTime previousExpiresAt;
    /** 新可空到期时间。 */
    private LocalDateTime expiresAt;
    /** 原因/工单，非凭据。 */
    private String reason;
    /** 数据库受理时间。 */
    private LocalDateTime createdAt;
}
