package cn.kokonexus.asset.infrastructure.rpc;

import cn.kokonexus.api.asset.CommunityAssetRpcService;
import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.asset.domain.AssetReferenceSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** asset-service：AssetPublicationLookup 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class AssetPublicationLookup {

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 5000, retries = 0)
    private IdentityRpcService identityRpcService;

    /** CommunityAssetRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 5000, retries = 0)
    private CommunityAssetRpcService communityAssetRpcService;

    /**
     * 两个域都成功才返回完整快照。不能用公开查询或单域成功替代全引用核验，
     * 也不能把 RPC 故障视为无人引用；只读结果仍存在绑定竞争，不用于对象删除。
     */
    public AssetReferenceSnapshot referenceSnapshot(String assetId) {
        boolean profileReferenced = false;
        boolean postReferenced = false;
        RuntimeException firstFailure = null;
        try {
            profileReferenced = identityRpcService.hasProfileAssetReference(assetId);
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            postReferenced = communityAssetRpcService.hasCoverReference(assetId);
        } catch (RuntimeException exception) {
            if (firstFailure == null) firstFailure = exception;
            else if (firstFailure != exception) firstFailure.addSuppressed(exception);
        }
        if (firstFailure != null) throw new AssetReferenceUnavailableException(firstFailure);
        return new AssetReferenceSnapshot(profileReferenced, postReferenced, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public boolean isPublished(String assetId) {
        RuntimeException firstFailure = null;
        try {
            if (identityRpcService.isPublishedProfileAsset(assetId)) return true;
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            if (communityAssetRpcService.isPublishedCover(assetId)) return true;
        } catch (RuntimeException exception) {
            if (firstFailure == null) firstFailure = exception;
            else firstFailure.addSuppressed(exception);
        }
        if (firstFailure != null) throw new AssetReferenceUnavailableException(firstFailure);
        return false;
    }
}
