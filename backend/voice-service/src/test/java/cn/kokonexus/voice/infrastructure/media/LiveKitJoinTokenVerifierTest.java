package cn.kokonexus.voice.infrastructure.media;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 实际SDK签发/HMAC解析检查；不把令牌测试视为LiveKit媒体或撤权验收。 */
class LiveKitJoinTokenVerifierTest {

    /** 隔离发行方，不访问任何服务器。 */ private static final String KEY = "isolated-media-api-key";
    /** 合成HMAC密钥，测试日志不打印JWT。 */ private static final String SECRET =
        "synthetic-media-signature-key-not-production";
    /** 合成固定UTC秒数。 */ private static final long NOW = Instant.parse("2026-10-06T00:00:00Z").getEpochSecond();
    /** 固定时钟的实际生产核验器。 */ private final LiveKitJoinTokenVerifier verifier = new LiveKitJoinTokenVerifier(
        KEY,
        SECRET,
        Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)
    );
    /** 测试JSON构建，不改变生产解析器。 */ private final ObjectMapper json = new ObjectMapper();

    @Test
    void opaqueSignedBindingIsOnlyAValidatedProjectionNotUserAuthorization() throws Exception {
        String identity = UUID.randomUUID().toString();
        var body = body();
        body.put("sub", identity);
        var video = (Map<String, Object>) body.get("video");
        video.put("canPublishData", false);
        video.put("canUpdateOwnMetadata", false);
        var verified = verifier.verify(signed(body), "42");
        assertThat(verified).isNotNull();
        assertThat(verified.binding()).isTrue();
        assertThat(verified.identity()).isEqualTo(identity);
        assertThat(verified.publish()).isTrue();
        // 用户归属必须由实际SQL绑定确认，不能从不含PII的subject推断。
        assertThat(verifier.verify(signed(body), "43")).isNotNull();
        video.remove("canPublishData");
        assertThat(verifier.verify(signed(body), "42")).isNull();
    }

    @Test
    void actualServerSdkTokenHasOnlyMicrophoneAndNoDataOrOwnMetadataPermission() throws Exception {
        var gateway = new LiveKitVoiceMediaGateway("http://127.0.0.1:45179", "ws://127.0.0.1:45179", KEY, SECRET);
        String token = gateway.issueJoinToken("koko-voice-9223372036854775807", 42, "合成用户");
        var current = new LiveKitJoinTokenVerifier(KEY, SECRET);
        var result = current.verify(token, "42");
        assertThat(result).isNotNull();
        assertThat(result.roomId()).isEqualTo(Long.MAX_VALUE);
        assertThat(current.verify(token, "43")).isNull();
        var body = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(body.path("video").path("canPublishData").booleanValue()).isFalse();
        assertThat(body.path("video").path("canUpdateOwnMetadata").booleanValue()).isFalse();
        assertThat(body.path("video").path("canPublishSources").get(0).asText()).isEqualTo("microphone");
        assertThat(result.toString()).doesNotContain("9223372036854775807");
    }

    @Test
    void validSignedClaimsStayBoundToCurrentUserAndCanonicalRoom() throws Exception {
        assertThat(verifier.verify(signed(body()), "42").roomId()).isEqualTo(9L);
        for (String user : Arrays.asList(null, "0", "042", "-1", "9223372036854775808", "43"))
            assertThat(verifier.verify(signed(body()), user)).isNull();
        for (String room : List.of("other-room", "koko-voice-0", "koko-voice-09", "koko-voice-9223372036854775808")) {
            var body = body();
            ((Map<String, Object>) body.get("video")).put("room", room);
            assertThat(verifier.verify(signed(body), "42")).isNull();
        }
    }

    @Test
    void expiredOpaqueTokenOnlyProjectsForExistingConnectionNeverNewAdmissionOrLegacy() throws Exception {
        var body = body();
        body.put("sub", UUID.randomUUID().toString());
        body.put("nbf", NOW - 200);
        body.put("exp", NOW - 80);
        var video = (Map<String, Object>) body.get("video");
        video.put("canPublishData", false);
        video.put("canUpdateOwnMetadata", false);
        String expired = signed(body);
        assertThat(verifier.verify(expired, "42")).isNull();
        assertThat(verifier.verifyRetained(expired, "42")).isNotNull();
        body.put("sub", "user-42");
        assertThat(verifier.verifyRetained(signed(body), "42")).isNull();
        body.put("sub", UUID.randomUUID().toString());
        body.put("nbf", Long.MIN_VALUE);
        assertThat(verifier.verifyRetained(signed(body), "42")).isNull();
        body.put("nbf", NOW - 4000);
        assertThat(verifier.verifyRetained(signed(body), "42")).isNull();
    }

    @Test
    void actualBoundSdkTokensMatchCurrentAudienceOrMicrophoneProjection() throws Exception {
        var sdk = new LiveKitVoiceMediaGateway(
            "http://127.0.0.1:1",
            "wss://app.example.invalid/api/media/livekit",
            KEY,
            SECRET
        );
        String id = UUID.randomUUID().toString();
        for (boolean publish : List.of(false, true)) {
            String token = sdk.issueBoundJoinToken("koko-voice-9", id, "合成成员", publish);
            var parsed = new LiveKitJoinTokenVerifier(KEY, SECRET).verify(token, "42");
            assertThat(parsed).isNotNull();
            assertThat(parsed.identity()).isEqualTo(id);
            assertThat(parsed.publish()).isEqualTo(publish);
            var claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            assertThat(claims.path("exp").asLong() - claims.path("nbf").asLong()).isEqualTo(120);
            assertThat(claims.path("video").path("canPublishData").booleanValue()).isFalse();
            assertThat(claims.path("video").path("canUpdateOwnMetadata").booleanValue()).isFalse();
        }
    }

    @Test
    void expiredFutureMalformedAndUnboundedTimeClaimsAreRejected() throws Exception {
        for (Object expiration : List.of(NOW, NOW - 1, NOW + 3601, Long.MAX_VALUE, "future", 1.5)) {
            var body = body();
            body.put("exp", expiration);
            assertThat(verifier.verify(signed(body), "42")).isNull();
        }
        var future = body();
        future.put("nbf", NOW + 1);
        assertThat(verifier.verify(signed(future), "42")).isNull();
        var wrong = body();
        wrong.put("iss", "isolated-other-issuer");
        assertThat(verifier.verify(signed(wrong), "42")).isNull();
    }

    @Test
    void elevatedVideoDataSipConfigAndAmbiguousPermissionsNeverPass() throws Exception {
        for (String field : List.of(
            "roomCreate",
            "roomAdmin",
            "roomList",
            "roomRecord",
            "ingressAdmin",
            "hidden",
            "canPublishData",
            "canUpdateOwnMetadata"
        )) {
            for (Object value : List.of(true, "false")) {
                var body = body();
                ((Map<String, Object>) body.get("video")).put(field, value);
                assertThat(verifier.verify(signed(body), "42")).isNull();
            }
        }
        var camera = body();
        ((Map<String, Object>) camera.get("video")).put("canPublishSources", List.of("microphone", "camera"));
        assertThat(verifier.verify(signed(camera), "42")).isNull();
        var source = body();
        ((Map<String, Object>) source.get("video")).remove("canPublishSources");
        assertThat(verifier.verify(signed(source), "42")).isNull();
        for (String field : List.of("sip", "roomConfig", "roomPreset")) {
            var body = body();
            body.put(field, Map.of("admin", true));
            assertThat(verifier.verify(signed(body), "42")).isNull();
        }
    }

    @Test
    void tamperingAlgorithmDuplicateKeysPaddingAndOversizeAreRejectedWithoutTokenInErrors() throws Exception {
        String valid = signed(body());
        String[] parts = valid.split("\\.");
        var altered = body();
        ((Map<String, Object>) altered.get("video")).put("room", "koko-voice-10");
        assertThat(
            verifier.verify(parts[0] + "." + encode(json.writeValueAsString(altered)) + "." + parts[2], "42")
        ).isNull();
        assertThat(verifier.verify(sign("{\"alg\":\"none\"}", json.writeValueAsString(body())), "42")).isNull();
        assertThat(
            verifier.verify(sign("{\"alg\":\"HS256\",\"alg\":\"HS256\"}", json.writeValueAsString(body())), "42")
        ).isNull();
        assertThat(
            verifier.verify(sign("{\"alg\":\"HS256\"}", "{\"iss\":\"x\",\"iss\":\"" + KEY + "\"}"), "42")
        ).isNull();
        assertThat(
            verifier.verify(
                sign("{\"alg\":\"HS256\",\"jku\":\"https://example.invalid/keys\"}", json.writeValueAsString(body())),
                "42"
            )
        ).isNull();
        for (String token : List.of(valid + "=", valid + ".extra", "x".repeat(8193), ".abc.def", "not-a-jwt"))
            assertThat(verifier.verify(token, "42")).isNull();
        assertThat(verifier.verify(null, "42")).isNull();
    }

    /** 旧平台缺data字段的10分钟JWT允许准入，业务状态仍必须另行判断。 */
    private Map<String, Object> body() {
        var video = new HashMap<String, Object>();
        video.put("room", "koko-voice-9");
        video.put("roomJoin", true);
        video.put("canSubscribe", true);
        video.put("canPublish", true);
        video.put("canPublishSources", List.of("microphone"));
        var body = new HashMap<String, Object>();
        body.put("iss", KEY);
        body.put("sub", "user-42");
        body.put("nbf", NOW);
        body.put("exp", NOW + 600);
        body.put("video", video);
        return body;
    }

    private String signed(Map<String, Object> body) throws Exception {
        return sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", json.writeValueAsString(body));
    }

    private String sign(String header, String body) throws Exception {
        String input = encode(header) + "." + encode(body);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return (
            input +
            "." +
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)))
        );
    }

    private static String encode(String source) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }
}
