package cn.kokonexus.asset.interfaces.rpc;

import cn.kokonexus.api.asset.AssetRpcService;
import cn.kokonexus.asset.application.AssetApplicationService;
import cn.kokonexus.asset.application.AssetBindingService;
import org.apache.dubbo.config.annotation.DubboService;

/** asset-service：AssetRpcServiceImpl 领域类型；字段单位、状态及可空性见各属性说明。 */
@DubboService(version = "1.0.0", timeout = 3000, retries = 0)
public class AssetRpcServiceImpl implements AssetRpcService {

    /** AssetApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final AssetApplicationService applicationService;
    /** 绑定意图用例，必须经 Spring 事务代理访问。 */
    private final AssetBindingService bindingService;

    public AssetRpcServiceImpl(AssetApplicationService applicationService, AssetBindingService bindingService) {
        this.applicationService = applicationService;
        this.bindingService = bindingService;
    }

    @Override
    public boolean isOwnedReady(String ownerId, String assetId, String purpose) {
        long parsedOwner;
        try {
            parsedOwner = Long.parseLong(ownerId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("用户标识无效", exception);
        }
        return applicationService.isOwnedReady(parsedOwner, assetId, purpose);
    }

    @Override
    public boolean beginBinding(String ownerId, String assetId, String purpose, String requestId) {
        return bindingService.begin(Long.parseLong(ownerId), assetId, purpose, requestId);
    }

    @Override
    public void completeBinding(String ownerId, String assetId, String purpose, String requestId) {
        bindingService.complete(Long.parseLong(ownerId), assetId, purpose, requestId);
    }

    @Override
    public void sealBinding(String ownerId, String assetId, String purpose, String requestId) {
        bindingService.complete(Long.parseLong(ownerId), assetId, purpose, requestId);
    }
}
