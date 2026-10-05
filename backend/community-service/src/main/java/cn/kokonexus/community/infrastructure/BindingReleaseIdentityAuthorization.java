package cn.kokonexus.community.infrastructure;

import cn.kokonexus.api.operations.BindingReleaseAuthorizationRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.outbox.binding.BindingReleaseReplayAuthorization;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.stereotype.Component;

/** 社区域资产确认第二跳，独立动作 RPC；任何授权故障不降级允许。 */
@Component
@RequiredArgsConstructor
public class BindingReleaseIdentityAuthorization implements BindingReleaseReplayAuthorization {

    /** 既有真实权限事实适配，不复用其通知确认方法。 */
    private final OutboxIdentityAuthorization authority;

    /** 新身份 Provider 缺失/旧版本不支持时失败关闭，不回退旧通知票据。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 3000, retries = 0)
    private BindingReleaseAuthorizationRpcService confirmations;

    @Override
    public void requirePermission(long actor, String permission) {
        authority.requirePermission(actor, permission);
    }

    @Override
    public void validateProof(
        long actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String token
    ) {
        try {
            confirmations.validate(Long.toString(actor), sessionHash, domain, command, token);
        } catch (RpcException failure) {
            throw new OperationsUnavailableException("资产恢复身份确认暂不可用");
        }
    }
}
