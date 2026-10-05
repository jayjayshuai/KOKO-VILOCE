package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;

/** 受保护的重放命令；身份来自服务端，命令不允许修改原事件载荷。 */
public record OutboxReplayCommand(
    @Schema(description = "本次受理幂等 UUID，异载荷不得复用") String requestId,
    @Schema(description = "原 DEAD 事件 UUID") String eventId,
    @Schema(description = "确认时看到的重放代次，过期版本返回冲突") long expectedGeneration,
    @Schema(description = "重放原因或工单，去除首尾空白后 10 到 500 字符，不填写密钥") String reason
) implements java.io.Serializable {}
