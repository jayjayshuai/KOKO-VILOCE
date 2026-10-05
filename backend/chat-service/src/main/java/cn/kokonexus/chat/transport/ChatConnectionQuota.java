package cn.kokonexus.chat.transport;

/** 共享连接租约；调用者必须在非EventLoop有界线程执行，UUID只能由服务器生成。 */
public interface ChatConnectionQuota {
    /** 认证后原子准入；false是配额耗尽，未知/失败回复必须抛出不可用异常。 */
    boolean acquire(long userId, String connectionId);
    /** 仅续期仍有效的原UUID，过期或已释放返回false，不能重新插入。 */
    boolean renew(long userId, String connectionId);
    /** 幂等释放自己的UUID；不存在亦成功，不删除其他连接或其他用户。 */
    void release(long userId, String connectionId);
}
