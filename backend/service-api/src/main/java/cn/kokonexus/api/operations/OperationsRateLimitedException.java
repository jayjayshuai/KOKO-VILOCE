package cn.kokonexus.api.operations;

/** 运营凭据确认尝试已限速，网关返回 429；不携带输入密码。 */
public class OperationsRateLimitedException extends RuntimeException {

    public OperationsRateLimitedException() {
        super("二次确认尝试过于频繁，请一分钟后重试");
    }
}
