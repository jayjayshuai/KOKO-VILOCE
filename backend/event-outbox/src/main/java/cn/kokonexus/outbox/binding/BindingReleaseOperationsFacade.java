package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleaseOperationsRpcService;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.BindingReleaseView;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/** 领域开关默认拒绝；经独立只读事务代理查询，持久化错误不暴露 SQL/表/节点。 */
public class BindingReleaseOperationsFacade implements BindingReleaseOperationsRpcService {

    /** 同时由全局运营与本域专用开关决定的实际启用事实。 */
    private final boolean enabled;
    /** 独立 Spring 代理，禁止自调用注解绕过事务边界。 */
    private final BindingReleaseReadService reads;

    public BindingReleaseOperationsFacade(boolean enabled, BindingReleaseReadService reads) {
        this.enabled = enabled;
        this.reads = Objects.requireNonNull(reads);
    }

    @Override
    public BindingReleasePage dead(String operatorId, BindingReleaseCursor cursor, int limit) {
        return persisted(operatorId, actor -> reads.dead(actor, cursor, limit));
    }

    @Override
    public BindingReleaseView detail(String operatorId, String requestId) {
        return persisted(operatorId, actor -> reads.detail(actor, requestId));
    }

    @Override
    public BindingReleaseSnapshot snapshot(String operatorId) {
        return persisted(operatorId, reads::snapshot);
    }

    private <T> T persisted(String operatorId, java.util.function.LongFunction<T> action) {
        if (!enabled) throw new OperationsUnavailableException("本域绑定释放排障未启用");
        long actor = actor(operatorId);
        return database(() -> action.apply(actor));
    }

    private static <T> T database(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException unavailable) {
            throw new OperationsUnavailableException("绑定释放持久化暂不可用");
        }
    }

    private static long actor(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("账号标识无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("账号标识无效");
        }
    }
}
