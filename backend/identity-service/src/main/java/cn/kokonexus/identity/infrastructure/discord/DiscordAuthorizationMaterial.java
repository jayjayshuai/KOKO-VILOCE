package cn.kokonexus.identity.infrastructure.discord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import lombok.Getter;

/** 短期授权材料；state本身是随机nonce。消费时必须另行核验会话和持久单次领取。 */
@Getter
public final class DiscordAuthorizationMaterial {

    /** 仅放入授权URL的256位随机state，不包含用户或网站会话信息。 */
    private final String state;
    /** 持久化索引只保存摘要，不保存原始state。 */
    private final String stateDigest;

    /** 服务端持有的PKCE秘密；持久化前须认证加密，不进入DTO/toString。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final String verifier;

    /** S256公开挑战，与本次verifier绑定。 */
    private final String challenge;

    private DiscordAuthorizationMaterial(String state, String verifier) {
        this.state = state;
        this.stateDigest = stateDigest(state);
        this.verifier = verifier;
        this.challenge = challenge(verifier);
    }

    public static DiscordAuthorizationMaterial generate() {
        SecureRandom random = new SecureRandom();
        byte[] state = new byte[32],
            verifier = new byte[32];
        random.nextBytes(state);
        random.nextBytes(verifier);
        var encoding = Base64.getUrlEncoder().withoutPadding();
        return new DiscordAuthorizationMaterial(encoding.encodeToString(state), encoding.encodeToString(verifier));
    }

    /** RFC7636允许字符和43～128长度；拒绝空/短挑战，不退回plain。 */
    public static String challenge(String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) throw new IllegalArgumentException(
            "Invalid PKCE verifier"
        );
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    public static String stateDigest(String state) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException(
            "Invalid authorization state"
        );
        return HexFormat.of().formatHex(sha256(state));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
