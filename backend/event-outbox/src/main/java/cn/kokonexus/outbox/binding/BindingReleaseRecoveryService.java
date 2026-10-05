package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseCommandBinding;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.outbox.operations.OutboxOperationsAuthorizer;
import cn.kokonexus.outbox.persistence.BindingReleaseRecoveryMapper;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原任务锁、幂等受理与同库审计；必须经独立 Spring Bean 调用。 */
@RequiredArgsConstructor
public class BindingReleaseRecoveryService {

    /** 资产恢复专用权限，不扩大通知能力。 */
    public static final String REPLAY_PERMISSION = "asset:binding:replay";
    /** 服务配置固定域。 */
    private final String domain;
    /** 同库状态机和追加审计。 */
    private final BindingReleaseRecoveryMapper mapper;
    /** 每次读取真实权限事实，故障关闭。 */
    private final OutboxOperationsAuthorizer authority;

    /** RC 下先锁原任务，再查审计，同任务并发串行且等待者可见刚提交的受理。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BindingReleaseReplayReceipt replay(long actor, BindingReleaseReplayCommand command) {
        permission(actor, REPLAY_PERMISSION);
        var normalized = BindingReleaseCommandBinding.normalize(domain, command);
        var task = mapper.lockRelease(normalized.requestId());
        if (task == null) throw new OperationsNotFoundException("绑定释放任务不存在");
        var existing = mapper.findAudit(normalized.commandId());
        if (existing != null) {
            if (
                !normalized.requestId().equals(existing.getRequestId()) ||
                actor != existing.getOperatorId() ||
                normalized.expectedGeneration() != existing.getExpectedGeneration() ||
                !normalized.reason().equals(existing.getReason())
            ) throw new IllegalStateException("人工命令标识已用于其他内容");
            return receipt(existing);
        }
        if (
            !"DEAD".equals(task.getStatus()) ||
            task.getGenerationAttempts() != 10 ||
            task.getLeaseToken() != null ||
            task.getLeaseUntil() != null ||
            normalized.expectedGeneration() != task.getReplayGeneration()
        ) throw new IllegalStateException("状态或代次已改变，请刷新后重新确认");
        if (task.getReplayGeneration() >= 10) throw new IllegalStateException("人工恢复已达十轮上限，必须调查根因");
        var audit = new BindingReleaseAudit();
        audit.setCommandId(normalized.commandId());
        audit.setRequestId(normalized.requestId());
        audit.setOperatorId(actor);
        audit.setExpectedGeneration(normalized.expectedGeneration());
        audit.setAcceptedGeneration(normalized.expectedGeneration() + 1);
        audit.setPreviousAttempts(task.getAttempts());
        audit.setPreviousGenerationAttempts(task.getGenerationAttempts());
        audit.setPreviousFailure(failure(task.getLastFailure()));
        audit.setReason(normalized.reason());
        if (
            mapper.requeue(normalized.requestId(), normalized.expectedGeneration()) != 1
        ) throw new IllegalStateException("恢复未受理");
        try {
            if (mapper.insertAudit(audit) != 1) throw new IllegalStateException("恢复审计未保存");
        } catch (DuplicateKeyException conflict) {
            throw new IllegalStateException("人工命令或代次重复，恢复未提交", conflict);
        }
        var saved = mapper.findAudit(normalized.commandId());
        if (saved == null) throw new IllegalStateException("恢复审计不可读取");
        return receipt(saved);
    }

    /** 原命令查询；404 不能证明在途请求已终止，前端必须保留幂等键。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingReleaseAuditView receipt(long actor, String requestId, String commandId) {
        permission(actor, BindingReleaseReadService.READ_PERMISSION);
        var target = BindingReleaseCommandBinding.uuid(requestId);
        var audit = mapper.findAudit(BindingReleaseCommandBinding.uuid(commandId));
        if (audit == null || !target.equals(audit.getRequestId())) throw new OperationsNotFoundException(
            "人工命令尚未观察到受理事实"
        );
        return view(audit);
    }

    /** 有界代次分页，不允许写/删除审计。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingReleaseAuditPage audits(long actor, String requestId, Integer beforeGeneration, int limit) {
        permission(actor, BindingReleaseReadService.READ_PERMISSION);
        if (
            limit < 1 || limit > 20 || (beforeGeneration != null && (beforeGeneration < 1 || beforeGeneration > 11))
        ) throw new IllegalArgumentException("审计分页无效");
        var rows = mapper.audits(BindingReleaseCommandBinding.uuid(requestId), beforeGeneration, limit + 1);
        var items = rows.stream().limit(limit).map(BindingReleaseRecoveryService::view).toList();
        return new BindingReleaseAuditPage(items, rows.size() > limit ? items.getLast().acceptedGeneration() : null);
    }

    private void permission(long actor, String permission) {
        if (actor <= 0) throw new IllegalArgumentException("操作者无效");
        authority.requirePermission(actor, permission);
    }

    private static BindingReleaseReplayReceipt receipt(BindingReleaseAudit audit) {
        return new BindingReleaseReplayReceipt(
            audit.getCommandId(),
            audit.getRequestId(),
            audit.getAcceptedGeneration(),
            audit.getCreatedAt()
        );
    }

    private static BindingReleaseAuditView view(BindingReleaseAudit audit) {
        return new BindingReleaseAuditView(
            audit.getCommandId(),
            audit.getRequestId(),
            audit.getOperatorId().toString(),
            audit.getExpectedGeneration(),
            audit.getAcceptedGeneration(),
            audit.getPreviousAttempts(),
            audit.getPreviousGenerationAttempts(),
            failure(audit.getPreviousFailure()),
            audit.getReason(),
            audit.getCreatedAt()
        );
    }

    private static String failure(String value) {
        return value == null || Set.of("release-unconfirmed", "lease-exhausted").contains(value) ? value : "unknown";
    }
}
