package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 202受理事实，既不代表 seal 已完成，也不代表资产被删除。 */
public record BindingReleaseReplayReceipt(
    @Schema(description = "人工命令 UUID") String commandId,
    @Schema(description = "原绑定 UUID") String requestId,
    @Schema(description = "本次受理代次，1～10") int acceptedGeneration,
    @Schema(description = "业务数据库受理时点原值，无时区") LocalDateTime acceptedAt
) implements java.io.Serializable {}
