package cn.kokonexus.chat.interfaces;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.kokonexus.common.api.ApiError;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 审核端授权错误使用稳定状态码，不返回内部权限对象或堆栈。 */
@RestControllerAdvice
public class ChatAuthenticationAdvice {

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError anonymous(NotLoginException ignored) {
        return ApiError.of("AUTH_REQUIRED", "登录已失效，请重新登录", "");
    }

    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError forbidden(NotPermissionException ignored) {
        return ApiError.of("FORBIDDEN", "当前账号没有聊天审核权限", "");
    }

    @ExceptionHandler({ QueryTimeoutException.class, TransactionTimedOutException.class })
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError timeout(RuntimeException ignored) {
        return ApiError.of("HISTORY_BUSY", "历史查询暂时繁忙，请稍后缩小搜索范围或重试", "");
    }
}
