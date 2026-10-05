package cn.kokonexus.community.interfaces.rpc;

import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseRecoveryRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.outbox.binding.BindingReleaseRecoveryFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 固定community域的恢复 Provider，秘密参数不进入日志，零自动重试。 */
@DubboService(version = "1.0.0", group = "community", timeout = 10000, retries = 0)
@RequiredArgsConstructor
public class BindingReleaseRecoveryRpcServiceImpl implements BindingReleaseRecoveryRpcService {

    /** 固定域开关、动作确认与独立事务用例。 */
    private final BindingReleaseRecoveryFacade facade;

    @Override
    public BindingReleaseReplayReceipt replay(
        String actor,
        String sessionHash,
        BindingReleaseReplayCommand command,
        String token
    ) {
        return facade.replay(actor, sessionHash, command, token);
    }

    @Override
    public BindingReleaseAuditView receipt(String actor, String requestId, String commandId) {
        return facade.receipt(actor, requestId, commandId);
    }

    @Override
    public BindingReleaseAuditPage audits(String actor, String requestId, Integer beforeGeneration, int limit) {
        return facade.audits(actor, requestId, beforeGeneration, limit);
    }
}
