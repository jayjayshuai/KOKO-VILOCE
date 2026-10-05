package cn.kokonexus.api.operations;

/** 无权限或二次确认不通过，网关返回 403，不撤销仍有效的普通登录会话。 */
public class OperationsAccessDeniedException extends RuntimeException {

    public OperationsAccessDeniedException(String message) {
        super(message);
    }
}
