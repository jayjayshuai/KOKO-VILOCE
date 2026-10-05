package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.AssetUsageMapper;
import cn.kokonexus.asset.infrastructure.persistence.MediaAssetMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short MySQL transactions only; no object storage calls while holding quota locks. */
@Service
public class AssetRegistrationService {

    /** AssetUsageMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final AssetUsageMapper usage;
    /** MediaAssetMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final MediaAssetMapper assets;
    /** 单用户图片字节数额度。 */
    private final long ownerBytes;
    /** 单用户图片数量额度。 */
    private final long ownerImages;
    /** 全局图片字节数额度。 */
    private final long totalBytes;
    /** 全局图片数量额度。 */
    private final long totalImages;

    public AssetRegistrationService(
        AssetUsageMapper usage,
        MediaAssetMapper assets,
        @Value("${koko.asset.quota.owner-bytes:104857600}") long ownerBytes,
        @Value("${koko.asset.quota.owner-images:100}") long ownerImages,
        @Value("${koko.asset.quota.total-bytes:1073741824}") long totalBytes,
        @Value("${koko.asset.quota.total-images:10000}") long totalImages
    ) {
        if (ownerBytes <= 0 || ownerImages <= 0 || totalBytes < ownerBytes || totalImages < ownerImages) {
            throw new IllegalStateException("Invalid media quota configuration");
        }
        this.usage = usage;
        this.assets = assets;
        this.ownerBytes = ownerBytes;
        this.ownerImages = ownerImages;
        this.totalBytes = totalBytes;
        this.totalImages = totalImages;
    }

    @Transactional
    public void register(MediaAsset asset) {
        if (
            asset.getOwnerId() == null ||
            asset.getOwnerId() <= 0 ||
            asset.getByteSize() == null ||
            asset.getByteSize() <= 0 ||
            !"PENDING".equals(asset.getStatus())
        ) {
            throw new IllegalArgumentException("Invalid media registration");
        }
        // Every allocator takes the bucket row first. Conditional UPDATE serializes
        // concurrent requests; a later failure rolls back both budgets and the intent.
        usage.ensureOwner(0);
        if (usage.reserve(0, asset.getByteSize(), totalBytes, totalImages) != 1) {
            throw new AssetQuotaExceededException(true);
        }
        usage.ensureOwner(asset.getOwnerId());
        if (usage.reserve(asset.getOwnerId(), asset.getByteSize(), ownerBytes, ownerImages) != 1) {
            throw new AssetQuotaExceededException(false);
        }
        if (assets.insert(asset) != 1) throw new IllegalStateException("媒体资产登记失败");
    }

    @Transactional
    public void deleteCleaning(MediaAsset asset) {
        if (assets.delete(Wrappers.<MediaAsset>query().eq("id", asset.getId()).eq("status", "CLEANING")) == 0) return;
        if (usage.release(0, asset.getByteSize()) != 1 || usage.release(asset.getOwnerId(), asset.getByteSize()) != 1) {
            throw new IllegalStateException("Media quota accounting mismatch");
        }
    }

    public QuotaView quota(long ownerId) {
        if (ownerId <= 0) throw new IllegalArgumentException("用户标识无效");
        var current = usage.selectById(ownerId);
        return new QuotaView(
            current == null ? 0 : current.getByteSize(),
            ownerBytes,
            current == null ? 0 : current.getImageCount(),
            ownerImages
        );
    }

    /** asset-service：QuotaView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record QuotaView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "已占用字节数，含未清理预留") long usedBytes,
        @io.swagger.v3.oas.annotations.media.Schema(description = "允许的最大字节数") long maxBytes,
        @io.swagger.v3.oas.annotations.media.Schema(description = "已占用图片数量") long usedImages,
        @io.swagger.v3.oas.annotations.media.Schema(description = "允许的最大图片数量") long maxImages
    ) {}
}
