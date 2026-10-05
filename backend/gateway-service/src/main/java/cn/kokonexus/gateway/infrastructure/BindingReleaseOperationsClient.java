package cn.kokonexus.gateway.infrastructure;

import cn.kokonexus.api.operations.BindingAttemptOperationsRpcService;
import cn.kokonexus.api.operations.BindingAttemptPage;
import cn.kokonexus.api.operations.BindingAttemptView;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleaseOperationsRpcService;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.BindingReleaseView;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import java.util.function.Function;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 两个显式固定引用，直播域没有此表；不构造客户端指定的 Dubbo group。 */
@Component
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
public class BindingReleaseOperationsClient {

    /** 固定身份库排障入口，失败不自动重试或读取其他域。 */
    @DubboReference(version = "1.0.0", group = "identity", check = false, timeout = 12000, retries = 0)
    private BindingReleaseOperationsRpcService identity;

    /** 固定社区库排障入口，无法读取直播库或资产所有者。 */
    @DubboReference(version = "1.0.0", group = "community", check = false, timeout = 12000, retries = 0)
    private BindingReleaseOperationsRpcService community;

    /** 固定新协议身份凭据引用，旧Provider缺方法失败关闭。 */
    @DubboReference(version = "1.0.0", group = "identity", check = false, timeout = 12000, retries = 0)
    private BindingAttemptOperationsRpcService identityAttempts;

    /** 固定社区新协议凭据，不允许直播域/客户端动态group。 */
    @DubboReference(version = "1.0.0", group = "community", check = false, timeout = 12000, retries = 0)
    private BindingAttemptOperationsRpcService communityAttempts;

    /** 新协议OPEN只读页，不执行结束判决。 */
    public BindingAttemptPage openAttempts(String domain, String actor, BindingReleaseCursor cursor, int limit) {
        return attemptCall(domain, service -> service.open(actor, cursor, limit));
    }

    /** 缺凭据404不能变成回滚证明。 */
    public BindingAttemptView attemptDetail(String domain, String actor, String requestId) {
        return attemptCall(domain, service -> service.detail(actor, requestId));
    }

    private <T> T attemptCall(String domain, Function<BindingAttemptOperationsRpcService, T> operation) {
        if (domain == null) throw new IllegalArgumentException("绑定凭据域无效");
        var service = switch (domain) {
            case "identity" -> identityAttempts;
            case "community" -> communityAttempts;
            default -> throw new IllegalArgumentException("仅支持identity/community绑定凭据域");
        };
        try {
            return operation.apply(service);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("绑定凭据写域暂不可用");
        }
    }

    public BindingReleasePage dead(String domain, String actor, BindingReleaseCursor cursor, int limit) {
        return call(domain, service -> service.dead(actor, cursor, limit));
    }

    public BindingReleaseView detail(String domain, String actor, String requestId) {
        return call(domain, service -> service.detail(actor, requestId));
    }

    public BindingReleaseSnapshot snapshot(String domain, String actor) {
        return call(domain, service -> service.snapshot(actor));
    }

    private <T> T call(String domain, Function<BindingReleaseOperationsRpcService, T> operation) {
        if (domain == null) throw new IllegalArgumentException("绑定释放域无效");
        var service = switch (domain) {
            case "identity" -> identity;
            case "community" -> community;
            default -> throw new IllegalArgumentException("仅支持 identity/community 绑定释放域");
        };
        try {
            return operation.apply(service);
        } catch (RpcException unavailable) {
            throw new OperationsUnavailableException("绑定释放业务域暂不可用");
        }
    }
}
