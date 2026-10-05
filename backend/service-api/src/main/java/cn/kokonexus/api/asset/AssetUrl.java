package cn.kokonexus.api.asset;

import java.util.UUID;

/** 平台公共契约：AssetUrl 领域类型；字段单位、状态及可空性见各属性说明。 */
public final class AssetUrl {

    private AssetUrl() {}

    public static String canonicalId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException("媒体资产标识无效");
            return canonical;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("媒体资产标识无效", exception);
        }
    }

    public static String publicUrl(String base, String id) {
        if (id == null) return null;
        return base.replaceAll("/+$", "") + "/" + canonicalId(id) + "/content";
    }
}
