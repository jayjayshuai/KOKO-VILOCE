package cn.kokonexus.api.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 高熵网站令牌的域隔离摘要，只做内部授权关联，不是可用于登录的凭据。 */
public final class WebsiteSessionScope {

    private WebsiteSessionScope() {}

    public static String fromToken(String token) {
        if (token == null || token.isBlank() || token.length() > 8192) throw new IllegalArgumentException(
            "网站会话无效"
        );
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    ("koko.voice.website.v1\0" + token).getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    public static boolean valid(String scope) {
        return scope != null && scope.matches("[0-9a-f]{64}");
    }
}
