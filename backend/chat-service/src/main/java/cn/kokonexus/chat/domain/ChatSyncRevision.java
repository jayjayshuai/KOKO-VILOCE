package cn.kokonexus.chat.domain;

/** 内部同步版本投影；不作为 HTTP 或 WebSocket 的授权凭据。 */
@lombok.Getter
@lombok.Setter
public class ChatSyncRevision {

    /** 受影响用户 ID；节点只查询本机已认证连接对应的用户。 */
    private Long userId;
    /** 单调提交版本；多个版本合并为一次查库提示是协议允许的行为。 */
    private Long revision;
}
