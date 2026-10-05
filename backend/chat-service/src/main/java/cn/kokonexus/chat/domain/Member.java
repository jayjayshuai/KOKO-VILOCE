package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 当前成员关系；移除物理关系后所有读写请求失去权限。 */
@Getter
@Setter
/** chat-service：Member 领域类型；字段单位、状态及可空性见各属性说明。 */
@TableName("chat_member")
public class Member {

    /** 关系 UUID。 */
    @TableId
    private String id;

    /** 所属会话 UUID。 */
    private String conversationId;
    /** 用户 ID。 */
    private Long userId;
    /** 加入时显示名称快照。 */
    private String displayName;
    /** 加入时公开用户名快照。 */
    private String handle;
    /** 可读消息必须大于此序号。 */
    private Long joinedSeq;
    /** 已读序号，只可前进。 */
    private Long readSeq;
}
