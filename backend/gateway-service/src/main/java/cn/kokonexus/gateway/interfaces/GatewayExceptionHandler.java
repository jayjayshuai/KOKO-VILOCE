package cn.kokonexus.gateway.interfaces;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

/** gateway-service：GatewayExceptionHandler 领域类型；字段单位、状态及可空性见各属性说明。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "GatewayExceptionHandler")
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(cn.kokonexus.api.voice.MediaAdmissionUnavailableException.class)
    ResponseEntity<ApiError> mediaRetirementUnavailable(RuntimeException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "1")
            .body(error("MEDIA_RETIREMENT_UNCONFIRMED", "网站会话已退出，媒体退场登记未完整确认", exchange));
    }

    @ExceptionHandler(cn.kokonexus.api.operations.OperationsNotFoundException.class)
    ResponseEntity<ApiError> operationsNotFound(RuntimeException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            error("OPERATIONS_NOT_FOUND", "事件或受理记录不存在", exchange)
        );
    }

    @ExceptionHandler({
        cn.kokonexus.api.operations.OperationsAccessDeniedException.class,
        cn.dev33.satoken.exception.NotPermissionException.class,
    })
    ResponseEntity<ApiError> operationsForbidden(RuntimeException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            error("OPERATIONS_FORBIDDEN", "无运营权限或本人二次确认未通过", exchange)
        );
    }

    @ExceptionHandler(cn.kokonexus.api.operations.OperationsRateLimitedException.class)
    ResponseEntity<ApiError> operationsRateLimited(RuntimeException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "60")
            .body(error("OPERATIONS_RATE_LIMITED", "二次确认尝试过于频繁，请稍后重试", exchange));
    }

    @ExceptionHandler(cn.kokonexus.api.operations.OperationsUnavailableException.class)
    ResponseEntity<ApiError> operationsUnavailable(RuntimeException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            error("OPERATIONS_UNAVAILABLE", "运营能力未启用或依赖暂不可用", exchange)
        );
    }

    @ExceptionHandler(GatewayResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(GatewayResourceNotFoundException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", exception.getMessage(), exchange));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ApiError> authenticationFailed(AuthenticationFailedException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            error("AUTHENTICATION_FAILED", exception.getMessage(), exchange)
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException exception, ServerWebExchange exchange) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", exception.getMessage(), exchange));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflict(IllegalStateException exception, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("CONFLICT", exception.getMessage(), exchange));
    }

    private ApiError error(String code, String message, ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        return new ApiError(code, message, requestId == null ? "" : requestId, Instant.now());
    }

    /** gateway-service：ApiError 领域类型；字段单位、状态及可空性见各属性说明。 */
    record ApiError(
        @io.swagger.v3.oas.annotations.media.Schema(description = "稳定的业务错误码") String code,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务提示消息，不输出内部堆栈") String message,
        @io.swagger.v3.oas.annotations.media.Schema(description = "请求追踪标识，不含密钥") String traceId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "服务端响应时间") Instant timestamp
    ) {}
}
