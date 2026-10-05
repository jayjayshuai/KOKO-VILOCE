package cn.kokonexus.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 只追加的人工审核审计；不得靠结案操作自动封禁账号。 */
@Getter
@Setter
@TableName("chat_report_review")
public class ChatReportReview {

    /** 审核记录 UUID。 */
    @TableId
    private String id;

    /** 举报 UUID，每份举报最多一项最终决定。 */
    private String reportId;
    /** 服务端认证的审核员用户 ID。 */
    private Long reviewerId;
    /** RESOLVED/REJECTED。 */
    private String decision;
    /** 审核说明，最多 500 字符。 */
    private String note;
    /** 审核时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
}
