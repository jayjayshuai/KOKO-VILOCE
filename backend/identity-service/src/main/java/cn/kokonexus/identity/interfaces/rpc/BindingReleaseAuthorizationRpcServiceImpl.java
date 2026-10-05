package cn.kokonexus.identity.interfaces.rpc;

import cn.kokonexus.api.operations.BindingReleaseAuthorizationRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.identity.application.BindingReleaseConfirmationService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.dao.DataAccessException;

/** 独立资产动作身份 Provider，不向业务域暴露密码摘要或原始数据库原因。 */
@DubboService(version = "1.0.0", timeout = 3000, retries = 0)
@RequiredArgsConstructor
public class BindingReleaseAuthorizationRpcServiceImpl implements BindingReleaseAuthorizationRpcService {

    /** 独立事务代理与开关。 */
    private final BindingReleaseConfirmationService service;

    @Override
    public ReplayConfirmation confirm(
        String actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String password
    ) {
        try {
            return service.confirm(actor, sessionHash, domain, command, password);
        } catch (DataAccessException failure) {
            throw new OperationsUnavailableException("资产恢复确认暂不可用");
        }
    }

    @Override
    public void validate(
        String actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String token
    ) {
        try {
            service.validate(actor, sessionHash, domain, command, token);
        } catch (DataAccessException failure) {
            throw new OperationsUnavailableException("资产恢复确认暂不可用");
        }
    }
}
