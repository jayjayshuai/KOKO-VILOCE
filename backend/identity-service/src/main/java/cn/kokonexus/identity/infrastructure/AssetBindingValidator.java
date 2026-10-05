package cn.kokonexus.identity.infrastructure;

import cn.kokonexus.api.asset.AssetRpcService;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import cn.kokonexus.outbox.binding.BindingAttemptCoordinator;
import cn.kokonexus.outbox.binding.BindingRelease;
import java.util.UUID;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** identity-service：AssetBindingValidator 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class AssetBindingValidator {

    /** 先独立提交凭据，再持同行锁包围业务事务和远端begin。 */
    private final BindingAttemptCoordinator coordinator;

    public AssetBindingValidator(BindingAttemptCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** AssetRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 5000, retries = 0)
    private AssetRpcService assetRpcService;

    /** 在资料事务完成前保留资产库意图；旧资产服务不支持新协议时失败关闭，不回退只读校验。 */
    public void assertOwnedReady(long ownerId, String assetId, String purpose) {
        String requestId = UUID.randomUUID().toString();
        coordinator.protect(
            ownerId,
            assetId,
            purpose,
            requestId,
            () -> {
                try {
                    return assetRpcService.beginBinding(String.valueOf(ownerId), assetId, purpose, requestId);
                } catch (RuntimeException exception) {
                    throw new ExternalDependencyUnavailableException("媒体资产绑定保护服务暂时不可用", exception);
                }
            },
            () -> assetRpcService.sealBinding(String.valueOf(ownerId), assetId, purpose, requestId)
        );
    }

    /** 提交/明确回滚/同行锁核对结束的任务才可领取；不降级调用旧complete。 */
    public void releaseCommitted(BindingRelease release) {
        assetRpcService.sealBinding(
            String.valueOf(release.getOwnerId()),
            release.getAssetId(),
            release.getPurpose(),
            release.getRequestId()
        );
    }
}
