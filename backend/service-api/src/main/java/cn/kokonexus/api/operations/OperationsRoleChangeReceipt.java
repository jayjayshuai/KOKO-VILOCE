package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 不可变受理事实；重复请求不会重新赋权、延长期限或改变审计。 */
public record OperationsRoleChangeReceipt(
    @Schema(description = "受理 UUID") String requestId,
    @Schema(description = "目标账号 ID") String userId,
    @Schema(description = "角色代码") String roleCode,
    @Schema(description = "ACTIVE/REVOKED 本次受理状态，不代表后续未变更") String status,
    @Schema(description = "本次受理版本，单调增加") long version,
    @Schema(description = "受理的可空到期时间，Asia/Shanghai") LocalDateTime expiresAt,
    @Schema(description = "数据库受理时间，Asia/Shanghai") LocalDateTime acceptedAt
) implements java.io.Serializable {}
