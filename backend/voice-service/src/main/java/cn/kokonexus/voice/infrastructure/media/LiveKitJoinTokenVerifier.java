package cn.kokonexus.voice.infrastructure.media;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 固定HS256/发行方/当前用户/语音范围核验；自建LiveKit的旧JWT不能靠删除成员自动撤销。 */
@Component
public class LiveKitJoinTokenVerifier {

    /** 原始令牌长度上限，与前端凭据上限一致。 */ private static final int MAX_TOKEN_LENGTH = 8192;
    /** 禁止客户端持有LiveKit管理能力，未知类型也不能被当成false。 */
    private static final Set<String> ADMIN_FLAGS = Set.of(
        "roomCreate",
        "roomList",
        "roomAdmin",
        "roomRecord",
        "ingressAdmin",
        "hidden"
    );
    /** 独立解析器拒绝重复键和尾随JSON，不改变业务全局ObjectMapper。 */
    private final ObjectMapper json = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 当前唯一发行方，不接受JWT自带kid、jwk或网络密钥。 */ private final String issuer;
    /** HMAC密钥仅内存持有，禁止访问器或日志。 */ private final byte[] secret;
    /** 服务端UTC时钟；测试使用明确固定时刻。 */ private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LiveKitJoinTokenVerifier(
        @Value("${livekit.api-key}") String issuer,
        @Value("${livekit.api-secret}") String secret
    ) {
        this(issuer, secret, Clock.systemUTC());
    }

    LiveKitJoinTokenVerifier(String issuer, String secret, Clock clock) {
        if (
            issuer == null || issuer.isBlank() || secret == null || secret.length() < 32
        ) throw new IllegalStateException("LiveKit准入签名配置不完整");
        this.issuer = issuer;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** 拒绝仅返回null，不复制JWT解析异常消息；数据库与身份决策另行当前核验。 */
    public VerifiedJoin verify(String token, String expectedUser) {
        return verify(token, expectedUser, false);
    }

    /** 仅供已准入WS的内部持续核验；过期UUID仍要核验签名及当前绑定，绝不可用于新握手。 */
    public VerifiedJoin verifyRetained(String token, String expectedUser) {
        return verify(token, expectedUser, true);
    }

    private VerifiedJoin verify(String token, String expectedUser, boolean retained) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH || !positive(expectedUser)) return null;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return null;
        for (String part : parts) if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) return null;
        try {
            var decoder = Base64.getUrlDecoder();
            var header = json.readTree(decoder.decode(parts[0]));
            if (
                header == null ||
                !header.isObject() ||
                !"HS256".equals(header.path("alg").asText()) ||
                (!header.path("typ").isMissingNode() && !"JWT".equals(header.path("typ").asText()))
            ) return null;
            var names = header.fieldNames();
            while (names.hasNext()) if (!Set.of("alg", "typ").contains(names.next())) return null;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] signature = decoder.decode(parts[2]);
            if (
                signature.length != 32 ||
                !MessageDigest.isEqual(
                    mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)),
                    signature
                )
            ) return null;
            var body = json.readTree(decoder.decode(parts[1]));
            if (body == null || !body.isObject() || !issuer.equals(body.path("iss").asText())) return null;
            String identity = body.path("sub").asText();
            boolean binding = false;
            if (!("user-" + expectedUser).equals(identity)) {
                if (!canonicalUuid(identity)) return null;
                binding = true;
            }
            long now = clock.instant().getEpochSecond();
            JsonNode exp = body.path("exp"),
                nbf = body.path("nbf");
            if (
                !exp.isIntegralNumber() ||
                !exp.canConvertToLong() ||
                exp.longValue() <= 0 ||
                ((!retained || !binding) && exp.longValue() <= now) ||
                exp.longValue() - now > 3600
            ) return null;
            if (
                retained &&
                binding &&
                (nbf.isMissingNode() || nbf.longValue() < 0 || exp.longValue() - nbf.longValue() > 3600)
            ) return null;
            if (
                !nbf.isMissingNode() &&
                (!nbf.isIntegralNumber() ||
                    !nbf.canConvertToLong() ||
                    nbf.longValue() > now ||
                    nbf.longValue() >= exp.longValue())
            ) return null;
            var video = body.path("video");
            if (!video.isObject() || !yes(video.path("roomJoin")) || !yes(video.path("canSubscribe"))) return null;
            for (String name : ADMIN_FLAGS) if (video.has(name) && !no(video.path(name))) return null;
            if (video.has("canUpdateOwnMetadata") && !no(video.path("canUpdateOwnMetadata"))) return null;
            // 旧平台令牌没有显式data字段；新令牌禁data，保持旧10分钟令牌的准入兼容。
            if (video.has("canPublishData") && !no(video.path("canPublishData"))) return null;
            if (binding && (!no(video.path("canPublishData")) || !no(video.path("canUpdateOwnMetadata")))) return null;
            // 当前Java SDK始终写出空sip对象；空对象不授予SIP权限，非空或非对象才拒绝。
            if (
                (body.has("sip") && (!body.path("sip").isObject() || body.path("sip").size() != 0)) ||
                body.has("roomConfig") ||
                body.has("roomPreset") ||
                video.has("destinationRoom") ||
                video.has("kind")
            ) return null;
            var publish = video.path("canPublish");
            var sources = video.path("canPublishSources");
            if (!publish.isBoolean()) return null;
            if (
                publish.booleanValue() &&
                (!sources.isArray() || sources.size() != 1 || !"microphone".equals(sources.get(0).asText()))
            ) return null;
            String provider = video.path("room").asText();
            if (!provider.startsWith("koko-voice-")) return null;
            String roomId = provider.substring("koko-voice-".length());
            if (!positive(roomId)) return null;
            return new VerifiedJoin(Long.parseLong(roomId), provider, identity, publish.booleanValue(), binding);
        } catch (java.io.IOException | IllegalArgumentException rejected) {
            return null;
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new cn.kokonexus.api.voice.MediaAdmissionUnavailableException();
        }
    }

    private static boolean yes(JsonNode value) {
        return value.isBoolean() && value.booleanValue();
    }

    private static boolean no(JsonNode value) {
        return value.isBoolean() && !value.booleanValue();
    }

    private static boolean positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean canonicalUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** 已验签投影不携带JWT；不等同于业务准入结果。 */
    public record VerifiedJoin(
        /** 凭据范围内的房间正数ID。 */ long roomId,
        /** 凭据范围内的内部房间名，只用于当前数据库匹配。 */ String providerRoomName,
        /** 已验签的旧平台身份或随机绑定UUID，仅后端使用。 */ String identity,
        /** 签名凭据希望发布标志，须与当前SQL席位相同。 */ boolean publish,
        /** 是否随机绑定身份；不能用于旧LEGACY准入。 */ boolean binding
    ) {
        /** 保留原Legacy内部投影构造；新控制身份必须显式提供完整已验签字段。 */
        public VerifiedJoin(long roomId, String providerRoomName) {
            this(roomId, providerRoomName, null, false, false);
        }

        @Override
        public String toString() {
            return "VerifiedVoiceJoin[redacted]";
        }
    }
}
