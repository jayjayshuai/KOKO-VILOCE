package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 会话事实；行锁保护序号分配与成员修改的一致顺序。 */
@Getter
@Setter
/** chat-service：Conversation 领域类型；字段单位、状态及可空性见各属性说明。 */
@TableName("chat_conversation")
public class Conversation {

    /** 会话 UUID。 */
    @TableId
    private String id;

    /** DIRECT 私信或 GROUP 群聊。 */
    private String kind;
    /** 排序用户 ID 对；群聊为空。 */
    private String directKey;
    /** 群主/创建者的用户 ID。 */
    private Long ownerId;
    /** 会话名称。 */
    private String title;
    /** 本会话最后提交的序号。 */
    private Long lastSeq;
    /** ACTIVE 可操作或 CLOSED 已解散。 */
    private String status;
    /** 创建时间，数据库采用 Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 最新消息或管理变更时间。 */
    private LocalDateTime updatedAt;
}
