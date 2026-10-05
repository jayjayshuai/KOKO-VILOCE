package cn.kokonexus.identity.interfaces.rpc;

import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.api.operations.OutboxOperationsRpcService;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import cn.kokonexus.outbox.operations.OutboxOperationsFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 固定 identity group；默认 Facade 拒绝所有调用，不能借内部 RPC 绕过当前授权。 */
@DubboService(version = "1.0.0", group = "identity", timeout = 10000, retries = 0)
@RequiredArgsConstructor
public class OutboxOperationsRpcServiceImpl implements OutboxOperationsRpcService {

    /** 固定本域、当前确认、独立事务用例边界。 */
    private final OutboxOperationsFacade facade;

    @Override
    public OutboxDeadPage dead(String operatorId, OutboxEventCursor cursor, int limit) {
        return facade.dead(operatorId, cursor, limit);
    }

    @Override
    public OutboxEventView detail(String operatorId, String eventId) {
        return facade.detail(operatorId, eventId);
    }

    @Override
    public OutboxAuditPage audits(String operatorId, String eventId, Long beforeGeneration, int limit) {
        return facade.audits(operatorId, eventId, beforeGeneration, limit);
    }

    @Override
    public OutboxAuditView receipt(String operatorId, String eventId, String requestId) {
        return facade.receipt(operatorId, eventId, requestId);
    }

    @Override
    public OutboxReplayReceipt replay(
        String operatorId,
        String sessionHash,
        OutboxReplayCommand command,
        String confirmationToken
    ) {
        return facade.replay(operatorId, sessionHash, command, confirmationToken);
    }
}
