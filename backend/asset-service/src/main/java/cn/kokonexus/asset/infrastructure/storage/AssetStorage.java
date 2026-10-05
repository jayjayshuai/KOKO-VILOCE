package cn.kokonexus.asset.infrastructure.storage;

/** asset-service：AssetStorage 领域类型；字段单位、状态及可空性见各属性说明。 */
public interface AssetStorage {
    void put(String objectKey, byte[] bytes, String contentType);
    byte[] get(String objectKey);
    void remove(String objectKey);
}
