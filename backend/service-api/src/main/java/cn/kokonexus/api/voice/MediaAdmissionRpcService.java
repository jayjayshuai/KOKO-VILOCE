package cn.kokonexus.api.voice;

/** 媒体信令入站再次核验，失败不降级允许；不代理LiveKit管理API。 */
public interface MediaAdmissionRpcService {
    /** 当前核验允许为true；凭据、归属或房间拒绝为false；依赖不可用抛固定异常。 */
    boolean admit(MediaAdmissionCommand command) throws MediaAdmissionUnavailableException;

    /** 已建立连接的持续核验，不复用新入会等待屏障；旧Provider未实现须失败关闭。 */
    default boolean retain(MediaAdmissionCommand command) {
        throw new MediaAdmissionUnavailableException();
    }
}
