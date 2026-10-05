package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 持久化纯文本消息；ACK 表示该记录已提交，不表示对方已读。 */
@Getter
@Setter
/** chat-service：ChatMessage 领域类型；字段单位、状态及可空性见各属性说明。 */
@TableName("chat_message")
public class ChatMessage {

    /** 服务端消息 UUID。 */
    @TableId
    private String id;

    /** 会话 UUID。 */
    private String conversationId;
    /** 会话内消息序号。 */
    private Long seq;
    /** 发送用户 ID。 */
    private Long senderId;
    /** 发送时名称快照。 */
    private String senderName;
    /** 客户端幂等 UUID。 */
    private String clientMessageId;
    /** 最多 2000 字符的纯文本正文。 */
    private String body;
    /** 服务端创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
