package cn.kokonexus.common.api;

/** 平台公共契约：领域失败语义；外部接口映射为对应状态码，不伪装成功。 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
