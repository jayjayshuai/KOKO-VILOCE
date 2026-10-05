package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 新协议写域凭据投影，不返回所有者或对象键；缺行不证明旧意图可结束。 */
public record BindingAttemptView(
    @Schema(description = "原绑定UUID，与保护/释放共享") String requestId,
    @Schema(description = "受管资产UUID，不含存储键") String assetId,
    @Schema(description = "AVATAR/BANNER/POST_COVER") String purpose,
    @Schema(description = "OPEN待核对/COMMITTED已提交/ABORTED已由同行锁封存，均不是删除许可") String status,
    @Schema(description = "业务库创建时点，无时区，保留微秒") LocalDateTime createdAt,
    @Schema(description = "业务库状态变化时点，无时区") LocalDateTime updatedAt
) implements java.io.Serializable {}
