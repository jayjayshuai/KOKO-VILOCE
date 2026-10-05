package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 本人实时运营权限投影；不暴露数据库实体或推导默认管理员。 */
public record OperationsAccess(
    @Schema(description = "运营能力是否由服务器启用，不代表当前账号有权限") boolean enabled,
    @Schema(description = "当前有效角色，无缓存，已撤销/过期角色不返回") List<String> roles,
    @Schema(description = "当前有效权限，无权限时为空列表") List<String> permissions
) implements java.io.Serializable {
    public OperationsAccess {
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }
}
