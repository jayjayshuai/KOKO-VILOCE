package cn.kokonexus.chat.infrastructure.media;

/** 私人媒体固定目标，不接受任意URL或公开房间名称。 */
public interface NativeCallMediaGateway {
    /** 在事务外限时准备最多8人的私人音频会话。 */
    void provision(String callId);
    /** 删除整个原通话；调用前由持久任务限定目标。 */
    void delete(String callId);
    /** 只移除已经退出的随机媒体身份。 */
    void remove(String callId, String identity);
    /** 同源受控信令入口，不是SFU管理地址。 */
    String publicUrl();
}
