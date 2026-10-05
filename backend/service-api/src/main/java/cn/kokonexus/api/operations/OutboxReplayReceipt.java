package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 返回受理事实而不是虚构通知已送达；重复请求返回同一份受理结果。 */
public record OutboxReplayReceipt(
    @Schema(description = "已保存的幂等受理请求 UUID") String requestId,
    @Schema(description = "保留不变的原事件 UUID") String eventId,
    @Schema(description = "已受理的重放代次，不因后续重试改变") long generation,
    @Schema(description = "业务库数据库受理时间，不含时区，不代表消费完成") LocalDateTime acceptedAt
) implements java.io.Serializable {}
