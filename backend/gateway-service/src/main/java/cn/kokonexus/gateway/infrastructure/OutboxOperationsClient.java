package cn.kokonexus.gateway.infrastructure;

import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.api.operations.OutboxOperationsRpcService;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import java.util.function.Function;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 三个显式 Dubbo 引用，不构造客户端指定 group，不对敏感写请求自动重试。 */
@Component
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
public class OutboxOperationsClient {

    /** 固定身份事件域。 */
    @DubboReference(version = "1.0.0", group = "identity", check = false, timeout = 12000, retries = 0)
    private OutboxOperationsRpcService identity;

    /** 固定社区事件域。 */
    @DubboReference(version = "1.0.0", group = "community", check = false, timeout = 12000, retries = 0)
    private OutboxOperationsRpcService community;

    /** 固定直播事件域。 */
    @DubboReference(version = "1.0.0", group = "live", check = false, timeout = 12000, retries = 0)
    private OutboxOperationsRpcService live;

    public OutboxDeadPage dead(String domain, String actor, OutboxEventCursor cursor, int limit) {
        return call(domain, service -> service.dead(actor, cursor, limit));
    }

    public OutboxEventView detail(String domain, String actor, String eventId) {
        return call(domain, service -> service.detail(actor, eventId));
    }

    public OutboxAuditPage audits(String domain, String actor, String eventId, Long before, int limit) {
        return call(domain, service -> service.audits(actor, eventId, before, limit));
    }

    public OutboxReplayReceipt replay(
        String domain,
        String actor,
        String sessionHash,
        OutboxReplayCommand command,
        String confirmationToken
    ) {
        return call(domain, service -> service.replay(actor, sessionHash, command, confirmationToken));
    }

    public OutboxAuditView receipt(String domain, String actor, String eventId, String requestId) {
        return call(domain, service -> service.receipt(actor, eventId, requestId));
    }

    private <T> T call(String domain, Function<OutboxOperationsRpcService, T> operation) {
        if (domain == null) throw new IllegalArgumentException("运营域无效");
        var service = switch (domain) {
            case "identity" -> identity;
            case "community" -> community;
            case "live" -> live;
            default -> throw new IllegalArgumentException("运营域无效");
        };
        try {
            return operation.apply(service);
        } catch (RpcException unavailable) {
            // 不携带 RPC 原异常，框架错误信息可能含调用参数或内部节点信息。
            throw new OperationsUnavailableException("事件域暂不可用；写请求结果可能已提交，请查询原请求");
        }
    }
}
