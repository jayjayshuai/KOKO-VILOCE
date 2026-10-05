package cn.kokonexus.chat.transport;

/** 配额依赖不可用或返回未知；不能映射为配额耗尽或业务REJECTED。 */
public final class ChatQuotaUnavailableException extends RuntimeException {

    public ChatQuotaUnavailableException(Throwable cause) {
        super("共享聊天配额暂不可用", cause);
    }
}
