package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 管理员显式赋权/撤权命令；不能修改自己，默认不允许注册者自举。 */
public record OperationsRoleChangeCommand(
    @Schema(description = "幂等请求标准 UUID，不得用于不同角色变更") String requestId,
    @Schema(description = "现有目标账号 ID，字符串防止精度丢失") String userId,
    @Schema(
        description = "OPERATIONS_ADMIN/NOTIFICATION_OPERATOR/NOTIFICATION_AUDITOR/ASSET_BINDING_AUDITOR/ASSET_BINDING_RECOVERY 固定白名单"
    )
    String roleCode,
    @Schema(description = "true 授予，false 撤销；撤销关系保留，不删除历史") boolean enabled,
    @Schema(description = "确认时的关系版本，尚未赋权时为 0") long expectedVersion,
    @Schema(description = "可空到期时间，Asia/Shanghai、最多六位小数；授予限未来一年内，撤销须空")
    LocalDateTime expiresAt,
    @Schema(description = "10～500 字符授权原因/工单，不含密码") String reason
) implements java.io.Serializable {}
