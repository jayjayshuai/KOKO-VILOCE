package cn.kokonexus.asset.application;

import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.asset.domain.AssetBindingIntent;
import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.AssetBindingIntentMapper;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 资产库内的持久化绑定保护；未来退役流程必须先持相同行锁并确认无意图。 */
@Service
@lombok.RequiredArgsConstructor
public class AssetBindingService {

    /** 仅允许已有图片业务用途，不接受客户端定义的新用途。 */
    private static final Set<String> PURPOSES = Set.of("AVATAR", "BANNER", "POST_COVER");
    /** 资产行锁与意图表使用同一个资产库事务。 */
    private final AssetBindingIntentMapper mapper;

    /** READY 校验与意图插入同事务；同 UUID 同指纹重试幂等，异指纹拒绝。 */
    @Transactional
    public boolean begin(long ownerId, String assetId, String purpose, String requestId) {
        validate(ownerId, assetId, purpose, requestId);
        MediaAsset asset = mapper.lockAsset(assetId);
        AssetBindingIntent completed = mapper.lockCompletion(requestId);
        if (completed != null) {
            requireFingerprint(completed, ownerId, assetId, purpose);
            return false;
        }
        if (
            asset == null ||
            asset.getOwnerId() != ownerId ||
            !purpose.equals(asset.getPurpose()) ||
            !"READY".equals(asset.getStatus())
        ) {
            return false;
        }
        AssetBindingIntent existing = mapper.selectById(requestId);
        if (existing != null) {
            if (
                existing.getOwnerId() != ownerId ||
                !assetId.equals(existing.getAssetId()) ||
                !purpose.equals(existing.getPurpose())
            ) {
                throw new IllegalArgumentException("媒体绑定请求标识已用于不同请求");
            }
            return true;
        }
        AssetBindingIntent intent = new AssetBindingIntent();
        intent.setRequestId(requestId);
        intent.setAssetId(assetId);
        intent.setOwnerId(ownerId);
        intent.setPurpose(purpose);
        if (mapper.insert(intent) != 1) {
            throw new IllegalStateException("媒体绑定保护未持久化");
        }
        return true;
    }

    /** 不读取引用域；调用者仅在已知本地事务终态后调用，重复释放不释放配额或删除对象。 */
    @Transactional
    public void complete(long ownerId, String assetId, String purpose, String requestId) {
        validate(ownerId, assetId, purpose, requestId);
        MediaAsset asset = mapper.lockAsset(assetId);
        AssetBindingIntent completed = mapper.lockCompletion(requestId);
        if (completed != null) {
            requireFingerprint(completed, ownerId, assetId, purpose);
            return;
        }
        if (asset != null && (asset.getOwnerId() != ownerId || !purpose.equals(asset.getPurpose()))) {
            throw new IllegalArgumentException("媒体绑定结束指纹不匹配");
        }
        AssetBindingIntent active = mapper.selectById(requestId);
        if (active != null) requireFingerprint(active, ownerId, assetId, purpose);
        // 即使 begin 尚未抵达，也先封存请求；无资产时同样保存，禁止未来迟到请求复活。
        if (mapper.insertCompletion(ownerId, assetId, purpose, requestId) != 1) {
            throw new IllegalStateException("媒体绑定结束标记未持久化");
        }
        mapper.deleteMatching(ownerId, assetId, purpose, requestId);
    }

    /** 结束标记、活动保护共享不可变指纹；不允许碰撞请求跨用户/资产完成。 */
    private void requireFingerprint(AssetBindingIntent intent, long ownerId, String assetId, String purpose) {
        if (
            intent.getOwnerId() != ownerId ||
            !assetId.equals(intent.getAssetId()) ||
            !purpose.equals(intent.getPurpose())
        ) {
            throw new IllegalArgumentException("媒体绑定请求标识已用于不同请求");
        }
    }

    /** 保证 SQL 参数规范，拒绝短 UUID、大小写别名及错误用途。 */
    private void validate(long ownerId, String assetId, String purpose, String requestId) {
        if (
            ownerId <= 0 ||
            AssetUrl.canonicalId(assetId) == null ||
            AssetUrl.canonicalId(requestId) == null ||
            purpose == null ||
            !PURPOSES.contains(purpose)
        ) {
            throw new IllegalArgumentException("媒体绑定请求参数无效");
        }
    }
}
