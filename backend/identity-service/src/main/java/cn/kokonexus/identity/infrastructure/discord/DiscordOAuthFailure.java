package cn.kokonexus.identity.infrastructure.discord;

/** 外部授权故障只携带分类，不保留响应正文、授权码、令牌或请求URL。 */
public final class DiscordOAuthFailure extends RuntimeException {

    /** 用于用例确定重试/重新授权提示，不把外部响应直接返回浏览器。 */
    public enum Kind {
        DENIED,
        UNAVAILABLE,
        INVALID_RESPONSE,
    }

    /** 本次失败类别；网络未知结果不等于授权未被消费。 */
    private final Kind kind;

    public DiscordOAuthFailure(Kind kind) {
        super("Discord authorization " + kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
