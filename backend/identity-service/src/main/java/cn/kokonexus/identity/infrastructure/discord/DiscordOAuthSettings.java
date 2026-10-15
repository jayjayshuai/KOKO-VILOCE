package cn.kokonexus.identity.infrastructure.discord;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 正式应用配置只从私有配置注入；不生成包含密钥的toString。 */
@Getter
@Setter
@ConfigurationProperties("koko.discord")
public class DiscordOAuthSettings {

    /** 默认关闭；凭据完整只表示可尝试，不能证明已通过供应商验收。 */
    private boolean enabled;
    /** Discord应用标识，不是平台用户ID。 */
    private String clientId = "";

    /** 服务端应用密钥，不向前端返回。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String clientSecret = "";

    /** 登记到Discord应用的固定HTTPS回调地址，禁止动态returnUrl。 */
    private String redirectUri = "";
    /** 当前加密密钥标识，只标识版本，不是密钥本身。 */
    private String encryptionKeyId = "";

    /** Base64编码的32字节随机密钥，仅从私有配置注入。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String encryptionKey = "";

    /** 轮换过渡期读取旧密文的私有密钥；最多保留7个旧版本。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Map<String, String> previousEncryptionKeys = new HashMap<>();

    /** 显式开启时配置不完整就拒绝启动，不能默默降级成可连接。 */
    public void validate() {
        if (!enabled) return;
        if (
            clientId == null ||
            !clientId.matches("[1-9][0-9]{16,19}") ||
            clientSecret == null ||
            clientSecret.length() < 16 ||
            clientSecret.length() > 512 ||
            !clientSecret.chars().allMatch(c -> c >= 33 && c <= 126)
        ) {
            throw new IllegalStateException("Discord application configuration is incomplete");
        }
        try {
            URI uri = URI.create(redirectUri);
            if (
                !"https".equals(uri.getScheme()) ||
                uri.getHost() == null ||
                uri.getUserInfo() != null ||
                uri.getRawFragment() != null ||
                uri.getRawQuery() != null ||
                (uri.getPort() != -1 && uri.getPort() != 443) ||
                uri.toASCIIString().length() > 1000
            ) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            // 不把URI解析异常链带入日志，原输入可能含用户误填的密钥。
            throw new IllegalStateException(
                "Discord callback must be a fixed HTTPS address without credentials, query or fragment"
            );
        }
        keyRing();
    }

    /** 只用于服务端构造加密器；不作为HTTP或RPC返回值。 */
    Map<String, byte[]> keyRing() {
        try {
            if (
                encryptionKeyId == null ||
                !encryptionKeyId.matches("[A-Za-z0-9_-]{1,32}") ||
                previousEncryptionKeys == null ||
                previousEncryptionKeys.size() > 7 ||
                previousEncryptionKeys.containsKey(encryptionKeyId)
            ) throw new IllegalArgumentException();
            Map<String, byte[]> result = new HashMap<>();
            result.put(encryptionKeyId, decodeKey(encryptionKey));
            previousEncryptionKeys.forEach((id, key) -> {
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException();
                result.put(id, decodeKey(key));
            });
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Discord encryption key configuration is incomplete");
        }
    }

    private static byte[] decodeKey(String encoded) {
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 32) throw new IllegalArgumentException();
        return key;
    }
}
