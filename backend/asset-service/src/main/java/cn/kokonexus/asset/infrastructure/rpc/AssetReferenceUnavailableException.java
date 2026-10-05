package cn.kokonexus.asset.infrastructure.rpc;

/** asset-service：领域失败语义；外部接口映射为对应状态码，不伪装成功。 */
public class AssetReferenceUnavailableException extends RuntimeException {

    public AssetReferenceUnavailableException(Throwable cause) {
        super("媒体引用状态暂时无法核验", cause);
    }
}
