package cn.kokonexus.identity.interfaces.rpc;

import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleaseOperationsRpcService;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.BindingReleaseView;
import cn.kokonexus.outbox.binding.BindingReleaseOperationsFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

/** 固定 identity 域；专用开关和当前权限在领域内部重新检查。 */
@DubboService(version = "1.0.0", group = "identity", timeout = 10000, retries = 0)
@RequiredArgsConstructor
public class BindingReleaseOperationsRpcServiceImpl implements BindingReleaseOperationsRpcService {

    /** 同域安全入口与独立只读事务，不借客户端指定表名/服务名。 */
    private final BindingReleaseOperationsFacade facade;

    @Override
    public BindingReleasePage dead(String operatorId, BindingReleaseCursor cursor, int limit) {
        return facade.dead(operatorId, cursor, limit);
    }

    @Override
    public BindingReleaseView detail(String operatorId, String requestId) {
        return facade.detail(operatorId, requestId);
    }

    @Override
    public BindingReleaseSnapshot snapshot(String operatorId) {
        return facade.snapshot(operatorId);
    }
}
