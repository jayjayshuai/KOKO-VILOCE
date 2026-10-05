package cn.kokonexus.outbox.operations;

import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.outbox.persistence.OutboxOperationsMapper;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 运营事实查询，不返回领取秘密，权限当前查询且 SQL 分页有界。 */
@RequiredArgsConstructor
public class OutboxReadService {

    /** 固定运营读权限。 */
    public static final String READ_PERMISSION = "notification:outbox:read";
    /** 本域固定表查询。 */
    private final OutboxOperationsMapper mapper;
    /** 当前账号事实权限，不缓存到分页结果。 */
    private final OutboxOperationsAuthorizer authorizer;

    /** 读取当前 DEAD 页；并发新死信或状态变化需刷新，不声称跨页快照一致。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OutboxDeadPage dead(long actor, OutboxEventCursor cursor, int limit) {
        authorize(actor);
        bounded(limit, 50);
        if (cursor != null) {
            if (
                cursor.createdAt() == null ||
                cursor.createdAt().getNano() % 1000 != 0 ||
                cursor.createdAt().getYear() < 1000 ||
                cursor.createdAt().getYear() > 9999
            ) {
                throw new IllegalArgumentException("游标时间必须非空且最多微秒精度");
            }
            cursor = new OutboxEventCursor(cursor.createdAt(), eventId(cursor.eventId()));
        }
        var rows = mapper.dead(cursor, limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(OutboxReadService::view).toList();
        var last = items.isEmpty() ? null : items.getLast();
        return new OutboxDeadPage(items, more ? new OutboxEventCursor(last.createdAt(), last.id()) : null);
    }

    /** 非 DEAD 事件也查询当前事实，受理后继续观察，不能把发送确认当成消费已完成。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OutboxEventView detail(long actor, String eventId) {
        authorize(actor);
        return view(required(eventId(eventId)));
    }

    /** 仅当前事件的追加审计；exclusive 代次和唯一索引避免重复或全站扫描。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OutboxAuditPage audits(long actor, String eventId, Long beforeGeneration, int limit) {
        authorize(actor);
        bounded(limit, 20);
        if (beforeGeneration != null && (beforeGeneration < 1 || beforeGeneration > 11)) {
            throw new IllegalArgumentException("审计游标必须为 1 到 11 的代次");
        }
        String id = eventId(eventId);
        required(id);
        var rows = mapper.audits(id, beforeGeneration, limit + 1);
        var items = rows.stream().limit(limit).map(OutboxReadService::auditView).toList();
        Long next = rows.size() > limit ? items.getLast().acceptedGeneration() : null;
        return new OutboxAuditPage(items, next);
    }

    /** 查询原命令的受理事实；未找到不代表在途请求已终止，客户端只能保留原命令重试。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OutboxAuditView receipt(long actor, String eventId, String requestId) {
        authorize(actor);
        String event = eventId(eventId);
        var audit = mapper.receipt(event, eventId(requestId));
        if (audit == null) throw new OperationsNotFoundException("原受理记录未观察到");
        return auditView(audit);
    }

    private static OutboxAuditView auditView(OutboxReplayAudit row) {
        return new OutboxAuditView(
            row.getRequestId(),
            row.getEventId(),
            row.getOperatorId().toString(),
            row.getExpectedGeneration(),
            row.getAcceptedGeneration(),
            row.getReason(),
            row.getPreviousAttempts(),
            row.getTotalAttemptsSnapshot().toString(),
            row.getPreviousError(),
            row.getCreatedAt()
        );
    }

    private OutboxOperationsEvent required(String id) {
        var event = mapper.detail(id);
        if (event == null) throw new OperationsNotFoundException("事件不存在");
        return event;
    }

    private void authorize(long actor) {
        if (actor <= 0) throw new IllegalArgumentException("操作者标识无效");
        authorizer.requirePermission(actor, READ_PERMISSION);
    }

    private static void bounded(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw new IllegalArgumentException("分页大小超出允许范围");
    }

    private static OutboxEventView view(OutboxOperationsEvent row) {
        Objects.requireNonNull(row);
        return new OutboxEventView(
            row.getId(),
            row.getEventType(),
            row.getRecipientId().toString(),
            row.getActorId().toString(),
            row.getResourceId(),
            row.getSummary(),
            row.getStatus(),
            row.getAttempts(),
            row.getTotalAttempts().toString(),
            row.getReplayGeneration(),
            row.getLastError(),
            row.getCreatedAt(),
            row.getNextAttemptAt(),
            row.getSentAt()
        );
    }

    private static String eventId(String value) {
        if (
            value == null ||
            !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        ) {
            throw new IllegalArgumentException("事件标识必须为标准 UUID");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
