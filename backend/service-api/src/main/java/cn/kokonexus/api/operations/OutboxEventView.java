package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 运营可见事件投影，不暴露领取 token 或任意载荷修改能力。 */
public record OutboxEventView(
    @Schema(description = "原始事件 UUID") String id,
    @Schema(description = "通知类型") String eventType,
    @Schema(description = "目标账号 ID，十进制字符串") String recipientId,
    @Schema(description = "业务操作者 ID，十进制字符串") String actorId,
    @Schema(description = "原业务资源 ID") String resourceId,
    @Schema(description = "原始通知摘要，仅有当前运营读权限可见") String summary,
    @Schema(description = "PENDING/RETRY/IN_FLIGHT/DEAD/SENT 当前状态") String status,
    @Schema(description = "本轮自动投递尝试数，不是人工重放数") int attempts,
    @Schema(description = "累计投递次数，十进制字符串防止 JS 精度丢失") String totalAttempts,
    @Schema(description = "人工重放代次，最多十次") long replayGeneration,
    @Schema(description = "数据库最后失败摘要，可空") String lastError,
    @Schema(description = "业务库创建时间，不含时区，原值用于游标") LocalDateTime createdAt,
    @Schema(description = "业务库下一投递时间，不含时区") LocalDateTime nextAttemptAt,
    @Schema(description = "业务库发送确认时间，可空；不是消费完成时间") LocalDateTime sentAt
) implements java.io.Serializable {}
