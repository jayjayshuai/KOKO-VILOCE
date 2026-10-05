package cn.kokonexus.identity.interfaces.rpc;

import cn.kokonexus.api.operations.BindingAttemptOperationsRpcService;
import cn.kokonexus.api.operations.BindingAttemptPage;
import cn.kokonexus.api.operations.BindingAttemptView;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.outbox.binding.BindingAttemptOperationsFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 固定identity写域新协议凭据，只读、当前授权与默认关闭。 */
@DubboService(version = "1.0.0", group = "identity", timeout = 10000, retries = 0)
@RequiredArgsConstructor
public class BindingAttemptOperationsRpcServiceImpl implements BindingAttemptOperationsRpcService {

    /** 本域双开关与独立只读事务代理。 */
    private final BindingAttemptOperationsFacade facade;

    @Override
    public BindingAttemptPage open(String operatorId, BindingReleaseCursor cursor, int limit) {
        return facade.open(operatorId, cursor, limit);
    }

    @Override
    public BindingAttemptView detail(String operatorId, String requestId) {
        return facade.detail(operatorId, requestId);
    }
}
