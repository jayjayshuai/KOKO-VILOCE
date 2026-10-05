package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingAttemptOperationsRpcService;
import cn.kokonexus.api.operations.BindingAttemptPage;
import cn.kokonexus.api.operations.BindingAttemptView;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import java.util.function.LongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;

/** 双开关、固定操作者与持久化故障语义；不泄露SQL。 */
@RequiredArgsConstructor
public class BindingAttemptOperationsFacade implements BindingAttemptOperationsRpcService {

    /** 全局运营和本域资产读能力均启用才可读取。 */
    private final boolean enabled;
    /** 经独立只读事务代理访问。 */
    private final BindingAttemptReadService reads;

    @Override
    public BindingAttemptPage open(String operatorId, BindingReleaseCursor cursor, int limit) {
        return call(operatorId, actor -> reads.open(actor, cursor, limit));
    }

    @Override
    public BindingAttemptView detail(String operatorId, String requestId) {
        return call(operatorId, actor -> reads.detail(actor, requestId));
    }

    private <T> T call(String actorId, LongFunction<T> action) {
        if (!enabled) throw new OperationsUnavailableException("本域绑定凭据读取未启用");
        if (actorId == null || !actorId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(
            "操作者标识无效"
        );
        long actor;
        try {
            actor = Long.parseLong(actorId);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("操作者标识无效");
        }
        try {
            return action.apply(actor);
        } catch (DataAccessException unavailable) {
            throw new OperationsUnavailableException("绑定凭据持久化暂不可用");
        }
    }
}
