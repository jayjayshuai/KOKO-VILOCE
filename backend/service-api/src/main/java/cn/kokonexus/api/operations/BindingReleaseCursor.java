package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** DEAD 页 exclusive 游标；原业务库微秒时间和 UUID 必须一起传递。 */
public record BindingReleaseCursor(
    @Schema(description = "原业务库创建时间，无时区，最多微秒精度") LocalDateTime createdAt,
    @Schema(description = "同一创建时间内的原绑定请求 UUID") String requestId
) implements java.io.Serializable {}
