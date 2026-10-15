package cn.kokonexus.chat.domain;

import lombok.Getter;
import lombok.Setter;

/** 与状态变化同事务的原命令收据，不保存授权秘密或返回过时的媒体状态。 */
@Getter
@Setter
public class NativeCallCommand {

    /** 所属通话UUID。 */
    private String callId;
    /** 已认证的操作用户。 */
    private Long actorId;
    /** 客户端原UUID。 */
    private String requestId;
    /** ACCEPT/DECLINE/LEAVE/END。 */
    private String commandType;
    /** 含原版本和会话作用域的命令指纹。 */
    private String requestHash;
    /** 此命令确认提交的控制版本，不等于当前通话仍未结束。 */
    private Long appliedVersion;
}
