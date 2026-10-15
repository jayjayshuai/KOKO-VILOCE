package cn.kokonexus.api.chat;

import cn.kokonexus.api.voice.MediaAdmissionCommand;
import cn.kokonexus.api.voice.MediaWebsiteSessionCommand;

/** 站内私信/群聊通话独立准入，不把公开语音房的授权复用于私人媒体。 */
public interface NativeCallMediaAdmissionRpcService {
    /** 首次握手要求未过期的本站限通话JWT、当前接听网站及成员同意。 */
    boolean admit(MediaAdmissionCommand command);
    /** 已建立的信令仍核验签名、网站及当前接听事实，不将JWT过期当作业务撤权。 */
    boolean retain(MediaAdmissionCommand command);
    /** 网站注销的持久清退登记；true不等于SFU已经确认离场。 */
    boolean retireWebsiteSession(MediaWebsiteSessionCommand command);
}
