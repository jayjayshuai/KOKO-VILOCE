package cn.kokonexus.identity.interfaces.rpc;

import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsAuthorizationRpcService;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.identity.application.OperationsAuthorityService;
import cn.kokonexus.identity.application.OperationsRoleService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 身份域运营授权提供者；开关关闭时拒绝写能力，不授予默认管理员。 */
@DubboService(version = "1.0.0", timeout = 3000, retries = 0)
@RequiredArgsConstructor
public class OperationsAuthorizationRpcServiceImpl implements OperationsAuthorizationRpcService {

    /** 真实 Spring 事务代理用例，限速为另一个 REQUIRES_NEW Bean。 */
    private final OperationsAuthorityService service;
    /** 审计与授权关系同库事务更新，不自动自举用户。 */
    private final OperationsRoleService roleService;

    @Override
    public OperationsAccess access(String userId) {
        return service.access(userId);
    }

    @Override
    public OperationsRoleChangeReceipt changeRole(
        String operatorId,
        OperationsRoleChangeCommand command,
        String password
    ) {
        return roleService.changeRole(operatorId, command, password);
    }

    @Override
    public void requirePermission(String userId, String permission) {
        service.requirePermission(userId, permission);
    }

    @Override
    public ReplayConfirmation confirmReplay(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String password
    ) {
        return service.confirmReplay(userId, sessionHash, domain, command, password);
    }

    @Override
    public void validateReplayConfirmation(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String confirmationToken
    ) {
        service.validateReplayConfirmation(userId, sessionHash, domain, command, confirmationToken);
    }
}
