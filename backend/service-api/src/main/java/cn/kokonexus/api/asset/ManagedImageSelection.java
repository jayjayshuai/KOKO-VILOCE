package cn.kokonexus.api.asset;

import java.util.function.Consumer;

/** Separates new managed bindings from legacy read-only external URLs. */
public record ManagedImageSelection(
    @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的受管媒体资产 UUID") String assetId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "旧版本外部图片地址，仅用于兼容读取") String legacyUrl
) {
    public static ManagedImageSelection choose(
        String submittedAssetId,
        String submittedUrl,
        String currentAssetId,
        String currentLegacyUrl,
        String publicBase,
        Consumer<String> verifyAsset
    ) {
        String id = AssetUrl.canonicalId(submittedAssetId);
        String url = submittedUrl == null ? "" : submittedUrl.trim();
        if (id != null) {
            verifyAsset.accept(id);
            return new ManagedImageSelection(id, null);
        }
        if (currentAssetId != null && url.equals(AssetUrl.publicUrl(publicBase, currentAssetId))) {
            verifyAsset.accept(currentAssetId);
            return new ManagedImageSelection(currentAssetId, null);
        }
        if (url.isEmpty()) return new ManagedImageSelection(null, null);
        if (currentAssetId == null && currentLegacyUrl != null && url.equals(currentLegacyUrl)) {
            return new ManagedImageSelection(null, currentLegacyUrl);
        }
        throw new IllegalArgumentException("请上传受管图片，不能填写新的外部图片地址");
    }
}
