package cn.kokonexus.outbox.operations;

import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.api.operations.OutboxOperationsRpcService;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.common.api.ResourceNotFoundException;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** 固定域 RPC 入口，开关/本人绑定校验后调用独立 Spring 事务 Bean，不自调用事务注解。 */
public class OutboxOperationsFacade implements OutboxOperationsRpcService {

    /** 服务器固定业务域，不从客户端 group/表名派生。 */
    private final String domain;
    /** 本域显式开关，默认关闭。 */
    private final boolean enabled;
    /** 当前查询代理用例。 */
    private final OutboxReadService reads;
    /** 同库重新排队/审计事务代理。 */
    private final OutboxReplayService replays;
    /** identity 当前确认与权限事实。 */
    private final OutboxDomainAuthorization authority;

    public OutboxOperationsFacade(
        String domain,
        boolean enabled,
        OutboxReadService reads,
        OutboxReplayService replays,
        OutboxDomainAuthorization authority
    ) {
        if (domain == null || !Set.of("identity", "community", "live").contains(domain)) {
            throw new IllegalArgumentException("运营业务域配置无效");
        }
        this.domain = domain;
        this.enabled = enabled;
        this.reads = java.util.Objects.requireNonNull(reads);
        this.replays = java.util.Objects.requireNonNull(replays);
        this.authority = java.util.Objects.requireNonNull(authority);
    }

    @Override
    public OutboxDeadPage dead(String operatorId, OutboxEventCursor cursor, int limit) {
        requireEnabled();
        return persisted(() -> reads.dead(actor(operatorId), cursor, limit));
    }

    @Override
    public OutboxEventView detail(String operatorId, String eventId) {
        requireEnabled();
        return persisted(() -> reads.detail(actor(operatorId), eventId));
    }

    @Override
    public OutboxAuditPage audits(String operatorId, String eventId, Long beforeGeneration, int limit) {
        requireEnabled();
        return persisted(() -> reads.audits(actor(operatorId), eventId, beforeGeneration, limit));
    }

    @Override
    public OutboxAuditView receipt(String operatorId, String eventId, String requestId) {
        requireEnabled();
        return persisted(() -> reads.receipt(actor(operatorId), eventId, requestId));
    }

    @Override
    public OutboxReplayReceipt replay(
        String operatorId,
        String sessionHash,
        OutboxReplayCommand command,
        String confirmationToken
    ) {
        requireEnabled();
        long actor = actor(operatorId);
        var normalized = ReplayCommandBinding.normalize(domain, command);
        authority.validateProof(actor, sessionHash, domain, normalized, confirmationToken);
        try {
            return persisted(() -> replays.replay(actor, normalized)); // 再次当前权限检查，之后同库事务。
        } catch (ResourceNotFoundException missing) {
            throw new OperationsNotFoundException("事件不存在");
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new OperationsUnavailableException("本域运营功能未启用");
    }

    private static <T> T persisted(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException unavailable) {
            throw new OperationsUnavailableException("本域持久化暂不可用");
        }
    }

    private static long actor(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("账号标识无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("账号标识无效");
        }
    }
}
