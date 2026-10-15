package cn.kokonexus.chat.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 显式邀请和接听事实，成员删除后重入的新UUID不继承旧同意。 */
@Getter
@Setter
public class NativeCallParticipant {

    /** 原生通话UUID。 */
    private String callId;
    /** 参与者平台ID。 */
    private Long userId;
    /** 邀请时的chat_member.id快照，不用可能重复的消息序号替代。 */
    private String memberId;
    /** 邀请时显示名快照。 */
    private String displayName;
    /** INVITED/ACCEPTED/DECLINED/LEFT/CANCELLED/MISSED。 */
    private String state;

    /** 明确接听的网站会话摘要，禁止对外序列化。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String websiteScope;

    /** 接听后生成的随机媒体身份，不以平台ID作SFU身份。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String mediaIdentity;

    /** UTC最后心跳时间，邀请状态为空。 */
    private LocalDateTime lastSeenAt;
    /** SQL计算的35秒租约有效性。 */
    private boolean leaseFresh;
}
