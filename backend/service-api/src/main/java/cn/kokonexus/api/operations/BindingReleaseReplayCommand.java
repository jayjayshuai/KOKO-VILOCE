package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;

/** 资产绑定专用人工受理命令，与通知事件重放及原绑定 requestId 分离。 */
public record BindingReleaseReplayCommand(
    @Schema(description = "人工受理幂等 UUID，超时必须保留原值") String commandId,
    @Schema(description = "已提交业务的原绑定 UUID，不是通知事件") String requestId,
    @Schema(description = "当前人工恢复代次，0～10") int expectedGeneration,
    @Schema(description = "排障工单与恢复原因，10～500字符") String reason
) implements java.io.Serializable {
    @Override
    public String toString() {
        return (
            "BindingReleaseReplayCommand[commandId=" + commandId + ", requestId=" + requestId + ", reason=<redacted>]"
        );
    }
}
