package cn.kokonexus.chat.domain;

import lombok.Getter;
import lombok.Setter;

/** 私人媒体清退任务内部投影，不向浏览器暴露原媒体身份或工作租约。 */
@Getter
@Setter
public class NativeCallRetirement {

    /** 原清退业务键，重试始终定位同一个原目标。 */ private String jobKey;
    /** 原通话UUID，不能被新通话复用。 */ private String callId;
    /** ROOM或者PARTICIPANT。 */ private String action;
    /** 原随机媒体身份，ROOM为空。 */ private String mediaIdentity;
    /** 已确认的网络失败次数，上限10。 */ private int attempts;
    /** 当前工作轮次UUID，迟到的确认必须匹配。 */ private String leaseToken;
}
