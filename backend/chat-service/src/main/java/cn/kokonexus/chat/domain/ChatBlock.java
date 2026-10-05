package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 主动用户私有的拉黑事实；不暴露给被拉黑用户。 */
@Getter
@Setter
@TableName("chat_block")
public class ChatBlock {

    /** 拉黑记录 UUID，用于稳定分页。 */
    @TableId
    private String id;

    /** 主动拉黑用户 ID。 */
    private Long ownerId;
    /** 目标用户 ID。 */
    private Long targetId;
    /** 目标公开用户名快照。 */
    private String targetHandle;
    /** 目标显示名称快照。 */
    private String targetName;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
