package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 只读追加审计，不包含确认秘密、密码、原领取令牌或对象键。 */
public record BindingReleaseAuditView(
    @Schema(description = "人工命令 UUID") String commandId,
    @Schema(description = "原绑定 UUID") String requestId,
    @Schema(description = "真实操作者账号，字符串防止前端精度丢失") String operatorId,
    @Schema(description = "确认旧代次，0～9") int expectedGeneration,
    @Schema(description = "受理新代次，1～10") int acceptedGeneration,
    @Schema(description = "受理前累计次数，不清零") int previousAttempts,
    @Schema(description = "受理前本代领取次数") int previousGenerationAttempts,
    @Schema(description = "固定失败类别，可空") String previousFailure,
    @Schema(description = "规范化恢复原因，只在授权页面显示") String reason,
    @Schema(description = "数据库受理时点原值，无时区") LocalDateTime createdAt
) implements java.io.Serializable {}
