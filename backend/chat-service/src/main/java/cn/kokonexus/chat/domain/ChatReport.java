package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 举报实体仅在业务层使用，证据必须由专用审核 DTO 授权后输出。 */
@Getter
@Setter
@TableName("chat_report")
public class ChatReport {

    /** 举报 UUID。 */
    @TableId
    private String id;

    /** 举报人 ID，不对其他普通用户公开。 */
    private Long reporterId;
    /** 真实消息 UUID。 */
    private String messageId;
    /** 真实会话 UUID。 */
    private String conversationId;
    /** 被举报消息的发送者 ID。 */
    private Long reportedUserId;
    /** 原因枚举，HARASSMENT/SPAM/THREAT/OTHER。 */
    private String reason;
    /** 举报人说明，最多 500 字符纯文本。 */
    private String detail;
    /** 消息证据快照，仅审核员可读，禁止日志输出。 */
    private String evidenceBody;
    /** PENDING/RESOLVED/REJECTED。 */
    private String status;
    /** 审核乐观锁版本，自 0 开始，只前进。 */
    private Long version;
    /** 人工审核说明；未审核为空。 */
    private String reviewNote;
    /** 审核时间，Asia/Shanghai；未审核为空。 */
    private LocalDateTime reviewedAt;
    /** 提交时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
