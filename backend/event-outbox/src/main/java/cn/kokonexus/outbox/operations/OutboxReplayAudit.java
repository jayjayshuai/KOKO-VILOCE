package cn.kokonexus.outbox.operations;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 同库追加审计实体；访问器用于 ORM 映射，不提供修改或删除用例。 */
@Getter
@Setter
public class OutboxReplayAudit {

    /** 不可复用为不同命令的受理 UUID。 */
    private String requestId;
    /** 原事件 UUID。 */
    private String eventId;
    /** 服务端确认的账号 ID。 */
    private Long operatorId;
    /** 命令原先确认的代次。 */
    private Long expectedGeneration;
    /** 已受理的新代次。 */
    private Long acceptedGeneration;
    /** 规范化后原因，重复请求须保持一致。 */
    private String reason;
    /** 重放前本轮尝试次数。 */
    private Integer previousAttempts;
    /** 重放前累计次数快照。 */
    private Long totalAttemptsSnapshot;
    /** 重放前失败快照，可为空；不可覆写历史记录。 */
    private String previousError;
    /** 业务库数据库受理时间，不含时区；不能假定 JDBC 时区改变数据库 CURRENT_TIMESTAMP。 */
    private LocalDateTime createdAt;
}
