package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 业务库时间和事件 UUID 组成的稳定降序游标，不能只按时间漏掉同微秒事件。 */
public record OutboxEventCursor(
    @Schema(description = "业务库 created_at 原值，不含时区，最多六位小数") LocalDateTime createdAt,
    @Schema(description = "同一时间的事件 UUID 排序断点") String eventId
) implements java.io.Serializable {}
