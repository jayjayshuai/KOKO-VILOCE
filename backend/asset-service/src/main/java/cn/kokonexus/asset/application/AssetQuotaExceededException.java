package cn.kokonexus.asset.application;

/** asset-service：领域失败语义；外部接口映射为对应状态码，不伪装成功。 */
public class AssetQuotaExceededException extends RuntimeException {

    /** 是否采用共享额度行。 */
    private final boolean sharedCapacity;

    public AssetQuotaExceededException(boolean sharedCapacity) {
        super(sharedCapacity ? "图片存储容量暂不足，请稍后重试" : "已达到图片库额度，请联系平台支持");
        this.sharedCapacity = sharedCapacity;
    }

    public boolean sharedCapacity() {
        return sharedCapacity;
    }
}
