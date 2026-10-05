package cn.kokonexus.common.api;

import java.time.Instant;

/** 平台公共契约：ApiError 领域类型；字段单位、状态及可空性见各属性说明。 */
public record ApiError(
    @io.swagger.v3.oas.annotations.media.Schema(description = "稳定的业务错误码") String code,
    @io.swagger.v3.oas.annotations.media.Schema(description = "业务提示消息，不输出内部堆栈") String message,
    @io.swagger.v3.oas.annotations.media.Schema(description = "请求追踪标识，不含密钥") String traceId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "服务端响应时间") Instant timestamp
) {
    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, Instant.now());
    }
}
