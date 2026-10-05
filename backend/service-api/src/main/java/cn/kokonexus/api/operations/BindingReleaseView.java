package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 已提交业务的释放事实投影；不返回所有者、领取令牌、内部错误或对象存储地址。 */
public record BindingReleaseView(
    @Schema(description = "原绑定请求 UUID，不是通知事件 ID") String requestId,
    @Schema(description = "受管资产 UUID，不含存储键") String assetId,
    @Schema(description = "AVATAR/BANNER/POST_COVER 原绑定用途") String purpose,
    @Schema(description = "PENDING/LEASED/SENT/DEAD，SENT 仅表示保护释放已确认") String status,
    @Schema(description = "永久累计领取次数，0～110；人工恢复也不清零") int attempts,
    @Schema(description = "人工受理代次，0～10，达到十代不再受理") int replayGeneration,
    @Schema(description = "本代已用领取次数，0～10") int generationAttempts,
    @Schema(description = "有界失败类别，可空，不包含原始 RPC 错误") String lastFailure,
    @Schema(description = "业务库创建时间原值，无时区，保留微秒游标，不换算成本地时间") LocalDateTime createdAt,
    @Schema(description = "业务库最近状态变化时间，无时区") LocalDateTime updatedAt,
    @Schema(description = "业务库下一自动尝试时间，无时区") LocalDateTime nextAttemptAt
) implements java.io.Serializable {}
