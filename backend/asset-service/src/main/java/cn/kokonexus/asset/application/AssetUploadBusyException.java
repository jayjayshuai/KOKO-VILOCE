package cn.kokonexus.asset.application;

/** asset-service：领域失败语义；外部接口映射为对应状态码，不伪装成功。 */
public class AssetUploadBusyException extends RuntimeException {

    public AssetUploadBusyException() {
        super("图片处理繁忙，请稍后重试");
    }
}
