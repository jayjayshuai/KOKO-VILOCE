package cn.kokonexus.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 平台公共契约：GlobalExceptionHandler 领域类型；字段单位、状态及可空性见各属性说明。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "GlobalExceptionHandler")
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError notFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return ApiError.of("NOT_FOUND", ex.getMessage(), traceId(request));
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiError forbidden(ForbiddenOperationException ex, HttpServletRequest request) {
        return ApiError.of("FORBIDDEN", ex.getMessage(), traceId(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return ApiError.of("BAD_REQUEST", ex.getMessage(), traceId(request));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError conflict(IllegalStateException ex, HttpServletRequest request) {
        return ApiError.of("CONFLICT", ex.getMessage(), traceId(request));
    }

    @ExceptionHandler(ExternalDependencyUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError dependencyUnavailable(ExternalDependencyUnavailableException ex, HttpServletRequest request) {
        return ApiError.of("DEPENDENCY_UNAVAILABLE", ex.getMessage(), traceId(request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var message = ex
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .findFirst()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .orElse("请求参数无效");
        return ApiError.of("VALIDATION_ERROR", message, traceId(request));
    }

    private String traceId(HttpServletRequest request) {
        var value = request.getHeader("X-Request-Id");
        return value == null ? "" : value;
    }
}
