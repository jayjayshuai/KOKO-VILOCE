package cn.kokonexus.asset.infrastructure.rpc;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 核验两个域的全状态查询；旧 Provider 缺少新方法时不将资产容器判为就绪。 */
@Component("assetReferences")
public class AssetReferencesHealthIndicator implements HealthIndicator {

    /** PROBE_ID 服务端协议常量，不接受客户端覆盖。 */
    private static final String PROBE_ID = "00000000-0000-0000-0000-000000000000";
    /** AssetPublicationLookup 外部或领域适配器，失败不伪装为业务成功。 */
    private final AssetPublicationLookup lookup;

    public AssetReferencesHealthIndicator(AssetPublicationLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Health health() {
        try {
            if (lookup.referenceSnapshot(PROBE_ID) == null) {
                throw new IllegalStateException("Missing complete reference snapshot");
            }
            return Health.up().build();
        } catch (RuntimeException exception) {
            // Do not leak RPC addresses, database details or nested exception messages.
            return Health.down().withDetail("reason", "reference-query-unavailable").build();
        }
    }
}
