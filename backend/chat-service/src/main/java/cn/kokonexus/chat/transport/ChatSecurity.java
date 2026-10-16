package cn.kokonexus.chat.transport;

import cn.dev33.satoken.stp.StpUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HTTP/WS 共用内部信任校验；令牌只在服务端内存中短暂使用，禁止写日志。 */
@Component
public class ChatSecurity {

    /** 仅网关持有的内部密钥字节。 */
    private final byte[] gatewayKey;
    /** 可发起跨协议握手/有副作用 HTTP 请求的精确 Origin 白名单。 */
    private final Set<String> origins;
    /** 与 HTTP Sa-Token 配置一致的会话 Cookie 名，不包含会话值。 */
    private final String tokenName;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatSecurity(
        @Value("${koko.chat.gateway-key}") String key,
        @Value("${koko.chat.allowed-origins}") String origins,
        @Value("${sa-token.token-name:koko-nexus-token}") String tokenName
    ) {
        if (key == null || key.length() < 32) throw new IllegalStateException("聊天内部网关密钥至少 32 字符");
        if (tokenName == null || !tokenName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) {
            throw new IllegalStateException("聊天会话 Cookie 名必须是有效的 HTTP token，最长 128 字符");
        }
        this.tokenName = tokenName;
        gatewayKey = key.getBytes(StandardCharsets.UTF_8);
        this.origins = Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    /** 独立协议测试沿用默认名称；Spring 只使用完整配置构造器。 */
    ChatSecurity(String key, String origins) {
        this(key, origins, "koko-nexus-token");
    }

    public String tokenName() {
        return tokenName;
    }

    public boolean trusted(String key) {
        return key != null && MessageDigest.isEqual(gatewayKey, key.getBytes(StandardCharsets.UTF_8));
    }

    public boolean allowedOrigin(String origin) {
        return origins.contains(origin);
    }

    /** 每次帧重新读共享会话；Redis 故障向上传递，不能降级为匿名或放行。 */
    public boolean active(long userId, String token) {
        if (token == null || token.isBlank()) return false;
        Object id = StpUtil.getLoginIdByToken(token);
        if (id == null || !Long.toString(userId).equals(id.toString())) return false;
        try {
            StpUtil.getStpLogic().checkActiveTimeout(token);
        } catch (cn.dev33.satoken.exception.NotLoginException expired) {
            return false;
        }
        StpUtil.getStpLogic().updateLastActiveToNow(token);
        return true;
    }
}
