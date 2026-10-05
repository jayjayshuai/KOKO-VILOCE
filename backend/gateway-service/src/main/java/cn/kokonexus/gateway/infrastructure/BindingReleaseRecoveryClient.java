package cn.kokonexus.gateway.infrastructure;

import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseAuthorizationRpcService;
import cn.kokonexus.api.operations.BindingReleaseRecoveryRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.ReplayConfirmation;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 固定两提交域及独立身份确认，零自动重试，旧 Provider 不降级通知确认。 */
@Component
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
public class BindingReleaseRecoveryClient {

    /** 身份独立动作 Provider，密码只能这一路发送。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 3000, retries = 0)
    private BindingReleaseAuthorizationRpcService confirmations;

    /** 固定身份提交库。 */
    @DubboReference(version = "1.0.0", group = "identity", check = false, timeout = 12000, retries = 0)
    private BindingReleaseRecoveryRpcService identity;

    /** 固定社区提交库。 */
    @DubboReference(version = "1.0.0", group = "community", check = false, timeout = 12000, retries = 0)
    private BindingReleaseRecoveryRpcService community;

    public ReplayConfirmation confirm(
        String domain,
        String actor,
        String sessionHash,
        BindingReleaseReplayCommand command,
        String password
    ) {
        provider(domain);
        return available(() -> confirmations.confirm(actor, sessionHash, domain, command, password));
    }

    public BindingReleaseReplayReceipt replay(
        String domain,
        String actor,
        String sessionHash,
        BindingReleaseReplayCommand command,
        String token
    ) {
        return available(() -> provider(domain).replay(actor, sessionHash, command, token));
    }

    public BindingReleaseAuditView receipt(String domain, String actor, String requestId, String commandId) {
        return available(() -> provider(domain).receipt(actor, requestId, commandId));
    }

    public BindingReleaseAuditPage audits(
        String domain,
        String actor,
        String requestId,
        Integer beforeGeneration,
        int limit
    ) {
        return available(() -> provider(domain).audits(actor, requestId, beforeGeneration, limit));
    }

    private BindingReleaseRecoveryRpcService provider(String domain) {
        if ("identity".equals(domain)) return identity;
        if ("community".equals(domain)) return community;
        throw new IllegalArgumentException("绑定恢复仅支持身份和社区域");
    }

    private static <T> T available(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RpcException failure) {
            throw new OperationsUnavailableException("绑定恢复依赖暂不可用");
        }
    }
}
