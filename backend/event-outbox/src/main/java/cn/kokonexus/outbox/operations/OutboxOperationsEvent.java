package cn.kokonexus.outbox.operations;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 有界查询映射，不把租约 token 加入运营响应，也不提供通用更新能力。 */
@Getter
@Setter
public class OutboxOperationsEvent {

    /** 原事件 UUID。 */
    private String id;
    /** 原通知类型。 */
    private String eventType;
    /** 原目标账号。 */
    private Long recipientId;
    /** 原业务操作者。 */
    private Long actorId;
    /** 原业务资源。 */
    private String resourceId;
    /** 原通知摘要，受运营读权限保护。 */
    private String summary;
    /** 当前投递状态。 */
    private String status;
    /** 当前自动重试轮次内尝试次数。 */
    private Integer attempts;
    /** 包含人工轮次的累计次数。 */
    private Long totalAttempts;
    /** 人工重放单调代次。 */
    private Long replayGeneration;
    /** 最后失败，可空。 */
    private String lastError;
    /** 原业务库创建时间，不含时区。 */
    private LocalDateTime createdAt;
    /** 原业务库下一投递时间。 */
    private LocalDateTime nextAttemptAt;
    /** 原业务库发送确认时间，可空，不是消费完成。 */
    private LocalDateTime sentAt;
}
