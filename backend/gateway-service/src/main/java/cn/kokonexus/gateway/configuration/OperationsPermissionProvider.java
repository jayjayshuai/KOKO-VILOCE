package cn.kokonexus.gateway.configuration;

import cn.dev33.satoken.stp.StpInterface;
import cn.kokonexus.gateway.infrastructure.OperationsAuthorizationClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Sa-Token 运营权限 Provider，读身份域事实，不缓存、不默认管理员；必须在 boundedElastic 调用。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.operations", name = "enabled", havingValue = "true")
public class OperationsPermissionProvider implements StpInterface {

    /** 当前身份权限 RPC 边界，依赖故障 fail-closed。 */
    private final OperationsAuthorizationClient client;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (!"login".equals(loginType) || loginId == null) return List.of();
        var access = client.access(loginId.toString());
        return access.enabled() ? access.permissions() : List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (!"login".equals(loginType) || loginId == null) return List.of();
        var access = client.access(loginId.toString());
        return access.enabled() ? access.roles() : List.of();
    }
}
