package cn.kokonexus.outbox.operations;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 重放事务锁定的状态快照，不携带通知正文，也不直接作为公开响应。 */
@Getter
@Setter
public class OutboxReplayState {

    /** 原事件 UUID。 */
    private String id;
    /** PENDING/RETRY/IN_FLIGHT/SENT/DEAD 当前状态。 */
    private String status;
    /** 本轮投递次数，重放后重置预算但不改变累计次数。 */
    private Integer attempts;
    /** 生命周期累计尝试次数。 */
    private Long totalAttempts;
    /** 单调重放代次，操作者确认版本与本值比较。 */
    private Long replayGeneration;
    /** 当前执行租约，DEAD 必须为空才能重放。 */
    private String claimToken;
    /** 当前租约期限，DEAD 必须为空才能重放。 */
    private LocalDateTime leaseUntil;
    /** 先前最后失败，进入审计后清理当前错误状态。 */
    private String lastError;
}
