package cn.kokonexus.identity.application;

/** 固定业务冲突提示，不附带供应商身份、授权码或令牌。 */
public final class DiscordLinkConflictException extends RuntimeException {

    public DiscordLinkConflictException(String message) {
        super(message);
    }
}
