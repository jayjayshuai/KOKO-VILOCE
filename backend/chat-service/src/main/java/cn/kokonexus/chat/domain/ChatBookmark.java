package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 个人消息收藏引用，不能作为正文权限凭据或直接序列化给其他成员。 */
@Getter
@Setter
@TableName("chat_bookmark")
public class ChatBookmark {

    /** 收藏引用 UUID，服务端生成。 */
    @TableId
    private String id;

    /** 主动收藏者用户 ID，只有本人可操作。 */
    private Long ownerId;
    /** 真实消息所属会话 UUID。 */
    private String conversationId;
    /** 真实消息 UUID，不存客户端提供的正文。 */
    private String messageId;
    /** 会话内序号，分页和入群边界判断依据。 */
    private Long messageSeq;
    /** 收藏时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
