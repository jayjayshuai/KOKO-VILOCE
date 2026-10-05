package cn.kokonexus.gateway.infrastructure;

import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsAuthorizationRpcService;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.ReplayConfirmation;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 运营身份 RPC 适配器；不把网络/服务错误降级为允许，不记录携带密码的调用参数。 */
@Component
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
public class OperationsAuthorizationClient {

    /** 本人权限/二次确认身份域契约；不用重试重复验证密码。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 3000, retries = 0)
    private OperationsAuthorizationRpcService service;

    public OperationsAccess access(String userId) {
        try {
            return service.access(userId);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("身份授权服务暂不可用");
        }
    }

    public ReplayConfirmation confirmReplay(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String password
    ) {
        try {
            return service.confirmReplay(userId, sessionHash, domain, command, password);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("身份二次确认服务暂不可用");
        }
    }

    /** 单次本人密码确认和原子角色审计，不对携带密码的 RPC 自动重试。 */
    public OperationsRoleChangeReceipt changeRole(String userId, OperationsRoleChangeCommand command, String password) {
        try {
            return service.changeRole(userId, command, password);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("身份角色管理服务暂不可用");
        }
    }
}
