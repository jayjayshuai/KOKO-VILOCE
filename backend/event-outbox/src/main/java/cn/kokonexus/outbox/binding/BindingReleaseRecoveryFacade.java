package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseCommandBinding;
import cn.kokonexus.api.operations.BindingReleaseRecoveryRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** 固定域独立恢复入口；读取与恢复开关分离，不把默认关闭当作空队列。 */
public class BindingReleaseRecoveryFacade implements BindingReleaseRecoveryRpcService {

    /** 固定提交域。 */
    private final String domain;
    /** 全局和绑定只读开关。 */
    private final boolean readEnabled;
    /** 三个开关共同批准人工恢复。 */
    private final boolean replayEnabled;
    /** 事务代理，禁止自调用。 */
    private final BindingReleaseRecoveryService service;
    /** 独立动作确认事实。 */
    private final BindingReleaseReplayAuthorization authority;

    public BindingReleaseRecoveryFacade(
        String domain,
        boolean readEnabled,
        boolean replayEnabled,
        BindingReleaseRecoveryService service,
        BindingReleaseReplayAuthorization authority
    ) {
        if (!Set.of("identity", "community").contains(domain)) throw new IllegalArgumentException("恢复域配置无效");
        this.domain = domain;
        this.readEnabled = readEnabled;
        this.replayEnabled = readEnabled && replayEnabled;
        this.service = java.util.Objects.requireNonNull(service);
        this.authority = java.util.Objects.requireNonNull(authority);
    }

    @Override
    public BindingReleaseReplayReceipt replay(
        String actor,
        String sessionHash,
        BindingReleaseReplayCommand command,
        String token
    ) {
        requireEnabled(replayEnabled);
        long operator = actor(actor);
        var normalized = BindingReleaseCommandBinding.normalize(domain, command);
        return persisted(() -> {
            authority.validateProof(operator, sessionHash, domain, normalized, token);
            return service.replay(operator, normalized);
        });
    }

    @Override
    public BindingReleaseAuditView receipt(String actor, String requestId, String commandId) {
        requireEnabled(readEnabled);
        return persisted(() -> service.receipt(actor(actor), requestId, commandId));
    }

    @Override
    public BindingReleaseAuditPage audits(String actor, String requestId, Integer beforeGeneration, int limit) {
        requireEnabled(readEnabled);
        return persisted(() -> service.audits(actor(actor), requestId, beforeGeneration, limit));
    }

    private static void requireEnabled(boolean enabled) {
        if (!enabled) throw new OperationsUnavailableException("绑定恢复能力未启用");
    }

    private static <T> T persisted(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException failure) {
            throw new OperationsUnavailableException("绑定恢复持久化暂不可用");
        }
    }

    private static long actor(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("操作者无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("操作者无效");
        }
    }
}
