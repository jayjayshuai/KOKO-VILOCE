package cn.kokonexus.asset.interfaces;

import cn.kokonexus.asset.application.AssetQuotaExceededException;
import cn.kokonexus.asset.application.AssetUploadBusyException;
import cn.kokonexus.asset.infrastructure.rpc.AssetReferenceUnavailableException;
import cn.kokonexus.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** asset-service：AssetExceptionHandler 领域类型；字段单位、状态及可空性见各属性说明。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "AssetExceptionHandler")
@RestControllerAdvice
public class AssetExceptionHandler {

    @ExceptionHandler(AssetUploadBusyException.class)
    public ResponseEntity<ApiError> processingBusy(AssetUploadBusyException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "2")
            .body(ApiError.of("ASSET_UPLOAD_BUSY", exception.getMessage(), request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(AssetQuotaExceededException.class)
    public ResponseEntity<ApiError> quotaExceeded(AssetQuotaExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(
            exception.sharedCapacity() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT
        ).body(
            ApiError.of(
                exception.sharedCapacity() ? "ASSET_STORAGE_CAPACITY" : "ASSET_QUOTA_EXCEEDED",
                exception.getMessage(),
                request.getHeader("X-Request-Id")
            )
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiError tooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return ApiError.of("IMAGE_TOO_LARGE", "图片不能超过 5 MiB", request.getHeader("X-Request-Id"));
    }

    @ExceptionHandler(AssetReferenceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError lookupUnavailable(AssetReferenceUnavailableException exception, HttpServletRequest request) {
        return ApiError.of("ASSET_REFERENCE_UNAVAILABLE", exception.getMessage(), request.getHeader("X-Request-Id"));
    }
}
