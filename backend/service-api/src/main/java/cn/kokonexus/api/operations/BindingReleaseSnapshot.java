package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 一条 SQL 的时点事实；各状态数量有界截断，不是全库精确总量或实时告警送达凭据。 */
public record BindingReleaseSnapshot(
    @Schema(description = "PENDING 数量，达到 1001 表示至少 1001，不再扫描") int pending,
    @Schema(description = "LEASED 数量，达到 1001 表示至少 1001") int leased,
    @Schema(description = "DEAD 数量，达到 1001 表示至少 1001") int dead,
    @Schema(description = "每个状态的采样上限，固定 1001") int sampleLimit,
    @Schema(description = "最旧 PENDING 年龄秒数；无 PENDING 返回 0，不含 LEASED") long oldestPendingAgeSeconds,
    @Schema(description = "最旧 DEAD 年龄秒数；无 DEAD 返回 0") long oldestDeadAgeSeconds,
    @Schema(description = "执行 SQL 的业务库当前会话时间，无时区；不是浏览器本地时间") LocalDateTime observedAt
) implements java.io.Serializable {}
