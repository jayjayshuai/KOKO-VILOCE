package cn.kokonexus.identity.infrastructure.discord;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM信封，AAD绑定用户/用途/密钥版本；不是完整持久化或撤销机制。 */
public final class DiscordTokenCipher {

    /** 不同用途不能互换密文。 */
    public enum Purpose {
        REFRESH_TOKEN,
        OAUTH_VERIFIER,
    }

    /** 写入采用的密钥标识；读取可保留旧钥实现显式轮换。 */
    private final String activeKey;
    /** 只保存在服务器内存的32字节密钥副本，不输出到日志。 */
    private final Map<String, SecretKeySpec> keys;
    /** 每次独立生成96位GCM nonce，不能重用固定IV。 */
    private final SecureRandom random = new SecureRandom();

    public DiscordTokenCipher(String activeKey, Map<String, byte[]> suppliedKeys) {
        if (
            suppliedKeys == null || suppliedKeys.isEmpty() || suppliedKeys.size() > 8
        ) throw new IllegalArgumentException("Invalid Discord key ring");
        Map<String, SecretKeySpec> copy = new HashMap<>();
        suppliedKeys.forEach((id, key) -> {
            if (
                id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || key == null || key.length != 32
            ) throw new IllegalArgumentException("Invalid Discord encryption key configuration");
            copy.put(id, new SecretKeySpec(key.clone(), "AES"));
        });
        if (!copy.containsKey(activeKey)) throw new IllegalArgumentException("Active Discord key unavailable");
        this.activeKey = activeKey;
        this.keys = Map.copyOf(copy);
    }

    /** owner是平台用户ID；调用方仍须验证其权限，AAD不代替鉴权。 */
    public String encrypt(String owner, Purpose purpose, String plaintext) {
        String context = context(owner, purpose, activeKey);
        if (
            plaintext == null || plaintext.isEmpty() || plaintext.getBytes(StandardCharsets.UTF_8).length > 4096
        ) throw new IllegalArgumentException("Invalid Discord protected value");
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKey), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.US_ASCII));
            var encoding = Base64.getUrlEncoder().withoutPadding();
            return (
                "v1." +
                activeKey +
                "." +
                encoding.encodeToString(nonce) +
                "." +
                encoding.encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)))
            );
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Discord encryption unavailable");
        }
    }

    /** 旧钥缺失/篡改/换用户/换用途都失败关闭；不返回未认证明文。 */
    public String decrypt(String owner, Purpose purpose, String envelope) {
        try {
            if (envelope == null || envelope.length() > 8192) throw new IllegalArgumentException();
            String[] parts = envelope.split("\\.", -1);
            if (
                parts.length != 4 || !parts[0].equals("v1") || !keys.containsKey(parts[1])
            ) throw new IllegalArgumentException();
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]),
                ciphertext = Base64.getUrlDecoder().decode(parts[3]);
            if (
                nonce.length != 12 || ciphertext.length < 17 || ciphertext.length > 4112
            ) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(parts[1]), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(owner, purpose, parts[1]).getBytes(StandardCharsets.US_ASCII));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Discord protected value could not be authenticated");
        }
    }

    private static String context(String owner, Purpose purpose, String keyId) {
        if (owner == null || !owner.matches("[1-9][0-9]{0,18}") || purpose == null) throw new IllegalArgumentException(
            "Invalid Discord protected value context"
        );
        return "koko-discord:v1:" + keyId + ":" + owner + ":" + purpose.name();
    }
}
