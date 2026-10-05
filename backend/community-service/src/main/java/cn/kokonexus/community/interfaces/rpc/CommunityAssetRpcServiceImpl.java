package cn.kokonexus.community.interfaces.rpc;

import cn.kokonexus.api.asset.CommunityAssetRpcService;
import cn.kokonexus.community.application.CreatorPostApplicationService;
import org.apache.dubbo.config.annotation.DubboService;

/** community-service：CommunityAssetRpcServiceImpl 领域类型；字段单位、状态及可空性见各属性说明。 */
@DubboService(version = "1.0.0", timeout = 3000, retries = 0)
public class CommunityAssetRpcServiceImpl implements CommunityAssetRpcService {

    /** CreatorPostApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final CreatorPostApplicationService postService;

    public CommunityAssetRpcServiceImpl(CreatorPostApplicationService postService) {
        this.postService = postService;
    }

    @Override
    public boolean isPublishedCover(String assetId) {
        return postService.isPublishedCover(assetId);
    }

    @Override
    public boolean hasCoverReference(String assetId) {
        return postService.hasCoverReference(assetId);
    }
}
