package cn.kokonexus.community.infrastructure;

import cn.kokonexus.api.operations.OperationsAuthorizationRpcService;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.outbox.operations.OutboxDomainAuthorization;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.stereotype.Component;

/** identity 权限事实适配；RPC 失效拒绝，不记录包含秘密凭据的调用参数或原异常。 */
@Component
public class OutboxIdentityAuthorization implements OutboxDomainAuthorization {

    /** 当前授权身份域，失败不自动重试或缓存允许结果。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 3000, retries = 0)
    private OperationsAuthorizationRpcService authority;

    @Override
    public void requirePermission(long actor, String permission) {
        try {
            authority.requirePermission(Long.toString(actor), permission);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("身份授权服务暂不可用");
        }
    }

    @Override
    public void validateProof(
        long actor,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String token
    ) {
        try {
            authority.validateReplayConfirmation(Long.toString(actor), sessionHash, domain, command, token);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("身份确认服务暂不可用");
        }
    }
}
