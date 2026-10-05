package cn.kokonexus.outbox.binding;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 同库追加审计内部映射，不提供通用修改删除 API，不自动输出原因。 */
@Getter
@Setter
public class BindingReleaseAudit {

    /** 人工幂等 UUID。 */
    private String commandId;
    /** 原绑定 UUID。 */
    private String requestId;
    /** 当前真实操作者。 */
    private Long operatorId;
    /** 确认旧代次。 */
    private Integer expectedGeneration;
    /** 受理新代次。 */
    private Integer acceptedGeneration;
    /** 受理前累计领取次数。 */
    private Integer previousAttempts;
    /** 受理前本代预算已用数。 */
    private Integer previousGenerationAttempts;
    /** 固定失败类别，可空。 */
    private String previousFailure;
    /** 规范化恢复原因，仅授权读取。 */
    private String reason;
    /** 业务库受理时间原值，无时区。 */
    private LocalDateTime createdAt;
}
