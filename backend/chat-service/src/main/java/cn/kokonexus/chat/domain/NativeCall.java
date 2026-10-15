package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 聊天原生通话事实；ACTIVE只说明接听同意，不证明已有真实音轨。 */
@Getter
@Setter
@TableName("chat_call")
public class NativeCall {

    /** 服务端通话UUID，不是公开语音房标识。 */
    @TableId
    private String id;

    /** 所属DIRECT或GROUP聊天会话。 */
    private String conversationId;
    /** 原发起者ID。 */
    private Long initiatorId;
    /** 创建意图的客户端UUID，原请求重试不可更换。 */
    private String clientRequestId;
    /** 包含参与者和网站作用域的不可变指纹。 */
    private String requestHash;
    /** RINGING/ACTIVE/ENDED控制状态，不是SDK连接状态。 */
    private String state;
    /** 单调控制版本；心跳不增加此版本。 */
    private Long version;
    /** 固定结束原因，未结束可空。 */
    private String endReason;
    /** UTC发起时间。 */
    private LocalDateTime createdAt;
    /** UTC来电截止时间，不由客户端延长。 */
    private LocalDateTime ringExpiresAt;
    /** UTC结束时间，可空。 */
    private LocalDateTime endedAt;

    /** SQL计算的当前响铃窗口，非持久字段。 */
    @TableField(exist = false)
    private boolean ringFresh;
}
