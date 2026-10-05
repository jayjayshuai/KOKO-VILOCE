package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 单事件追加审计投影，不提供修改、删除或把审计当作送达凭证。 */
public record OutboxAuditView(
    @Schema(description = "原受理请求 UUID") String requestId,
    @Schema(description = "原事件 UUID") String eventId,
    @Schema(description = "受理操作者 ID，字符串") String operatorId,
    @Schema(description = "命令预期代次") long expectedGeneration,
    @Schema(description = "实际已受理代次") long acceptedGeneration,
    @Schema(description = "规范化受理原因") String reason,
    @Schema(description = "受理前本轮尝试数") int previousAttempts,
    @Schema(description = "受理前累计次数，十进制字符串") String totalAttemptsSnapshot,
    @Schema(description = "受理前失败摘要，可空") String previousError,
    @Schema(description = "业务库受理时间，不含时区；不代表送达") LocalDateTime createdAt
) implements java.io.Serializable {}
