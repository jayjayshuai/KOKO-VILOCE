package cn.kokonexus.chat.transport;

import cn.dev33.satoken.stp.StpInterface;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 显式服务端名单提供初期审核角色；默认空，不从客户端头或注册顺序推导管理员。 */
@Component
public class ChatPermissionProvider implements StpInterface {

    /** 查询审核证据所需权限。 */
    public static final String READ = "chat:reports:read";
    /** 写入最终审核决定所需权限。 */
    public static final String REVIEW = "chat:reports:review";
    /** 由拥有服务器配置权限的人明确指定的审核员 ID；不提供公开修改接口。 */
    private final Set<String> moderators;

    public ChatPermissionProvider(@Value("${koko.chat.moderator-user-ids:}") String configuredIds) {
        if (configuredIds == null || configuredIds.isBlank()) {
            moderators = Set.of();
            return;
        }
        moderators = Arrays.stream(configuredIds.split(",", -1))
            .map(String::trim)
            .peek(id -> {
                if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) < 1) {
                    throw new IllegalArgumentException("审核员配置必须为逗号分隔的有效用户 ID");
                }
            })
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return authorized(loginId, loginType) ? List.of(READ, REVIEW) : List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return authorized(loginId, loginType) ? List.of("CHAT_MODERATOR") : List.of();
    }

    private boolean authorized(Object loginId, String loginType) {
        return "login".equals(loginType) && loginId != null && moderators.contains(loginId.toString());
    }
}
