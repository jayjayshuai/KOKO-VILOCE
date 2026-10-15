package cn.kokonexus.chat.infrastructure.media;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.livekit.server.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 私人音频凭据只授权随机通话/随机媒体身份及麦克风，签名不能替代当前SQL同意。 */
@Component
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallTokens {

    /** 发行方仅从服务端配置取得，绝不接收JWT中的远程密钥地址。 */
    private final String issuer;
    /** 仅本进程签发/验签使用的HMAC秘密，不生成访问器。 */
    private final String secret;
    /** UTC期限，测试可固定时刻。 */
    private final Clock clock;
    /** 重复键或尾随JSON拒绝，不改全局业务JSON配置。 */
    private final ObjectMapper json = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 不接受LiveKit管理、录制、隐藏或任意其他发布能力。 */
    private static final Set<String> GRANTS = Set.of(
        "room",
        "roomJoin",
        "canPublish",
        "canSubscribe",
        "canPublishData",
        "canUpdateOwnMetadata",
        "canPublishSources"
    );

    @org.springframework.beans.factory.annotation.Autowired
    public NativeCallTokens(
        @Value("${livekit.api-key:}") String issuer,
        @Value("${livekit.api-secret:}") String secret
    ) {
        this(issuer, secret, Clock.systemUTC());
    }

    NativeCallTokens(String issuer, String secret, Clock clock) {
        if (
            issuer == null || issuer.isBlank() || secret == null || secret.length() < 32
        ) throw new IllegalStateException("私人通话媒体配置不完整");
        this.issuer = issuer;
        this.secret = secret;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /** 一分钟仅限制初次入会；撤权依赖信令持续核验及SFU清退，不宣称JWT被删除。 */
    public String issue(String callId, String identity, String displayName) {
        uuid(callId);
        uuid(identity);
        if (
            displayName == null || displayName.isBlank() || displayName.length() > 80
        ) throw new IllegalArgumentException("通话显示名无效");
        var token = new AccessToken(issuer, secret);
        token.setIdentity(identity);
        token.setName(displayName);
        token.setTtl(60_000L);
        token.setNotBefore(Date.from(clock.instant()));
        token.setExpiration(Date.from(clock.instant().plusSeconds(60)));
        token.addGrants(
            new RoomName(room(callId)),
            new RoomJoin(true),
            new CanPublish(true),
            new CanSubscribe(true),
            new CanPublishData(false),
            new CanUpdateOwnMetadata(false),
            new CanPublishSources(List.of("microphone"))
        );
        return token.toJwt();
    }

    /** 仅返回已验签的范围；首次必须未过期，持续核验仍要求签名、期限形状和当前SQL绑定。 */
    public Join verify(String value, boolean entry) {
        if (value == null || value.length() > 8192) return null;
        var parts = value.split("\\.", -1);
        if (parts.length != 3) return null;
        for (var part : parts) if (!part.matches("[A-Za-z0-9_-]+")) return null;
        try {
            var decoder = Base64.getUrlDecoder();
            var header = json.readTree(decoder.decode(parts[0]));
            if (
                header == null ||
                !header.isObject() ||
                !"HS256".equals(header.path("alg").asText()) ||
                (header.has("typ") && !"JWT".equals(header.path("typ").asText()))
            ) return null;
            var keys = header.fieldNames();
            while (keys.hasNext()) if (!Set.of("alg", "typ").contains(keys.next())) return null;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var signature = decoder.decode(parts[2]);
            if (
                signature.length != 32 ||
                !MessageDigest.isEqual(
                    signature,
                    mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII))
                )
            ) return null;
            var body = json.readTree(decoder.decode(parts[1]));
            if (body == null || !body.isObject() || !issuer.equals(body.path("iss").asText())) return null;
            if (
                (body.has("sip") && (!body.path("sip").isObject() || body.path("sip").size() != 0)) ||
                body.has("roomConfig") ||
                body.has("roomPreset")
            ) return null;
            var nbf = body.path("nbf");
            var exp = body.path("exp");
            long now = clock.instant().getEpochSecond();
            if (
                !nbf.isIntegralNumber() ||
                !nbf.canConvertToLong() ||
                !exp.isIntegralNumber() ||
                !exp.canConvertToLong() ||
                nbf.longValue() < 0 ||
                nbf.longValue() > now ||
                exp.longValue() <= nbf.longValue() ||
                exp.longValue() - nbf.longValue() > 3600 ||
                (entry && exp.longValue() <= now)
            ) return null;
            var video = body.path("video");
            if (!video.isObject()) return null;
            keys = video.fieldNames();
            while (keys.hasNext()) if (!GRANTS.contains(keys.next())) return null;
            for (var key : List.of("roomJoin", "canPublish", "canSubscribe"))
                if (!video.path(key).isBoolean() || !video.path(key).booleanValue()) return null;
            for (var key : List.of("canPublishData", "canUpdateOwnMetadata"))
                if (!video.path(key).isBoolean() || video.path(key).booleanValue()) return null;
            var sources = video.path("canPublishSources");
            if (!sources.isArray() || sources.size() != 1 || !"microphone".equals(sources.get(0).asText())) return null;
            String provider = video.path("room").asText();
            if (!provider.startsWith("koko-call-")) return null;
            String callId = provider.substring("koko-call-".length());
            String identity = body.path("sub").asText();
            uuid(callId);
            uuid(identity);
            return new Join(callId, identity);
        } catch (java.io.IOException | IllegalArgumentException rejected) {
            return null;
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new cn.kokonexus.api.voice.MediaAdmissionUnavailableException();
        }
    }

    public static String room(String callId) {
        uuid(callId);
        return "koko-call-" + callId;
    }

    private static void uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(
            "通话媒体UUID无效"
        );
    }

    /** 内部已验签投影，不含原JWT，不作为准入成功证明。 */
    public record Join(/** 已签名的私人通话UUID。 */ String callId, /** 已签名的随机媒体身份。 */ String identity) {
        @Override
        public String toString() {
            return "NativeCallJoin[redacted]";
        }
    }
}
