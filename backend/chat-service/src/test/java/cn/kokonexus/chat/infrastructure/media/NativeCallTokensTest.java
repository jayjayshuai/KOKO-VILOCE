package cn.kokonexus.chat.infrastructure.media;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 用真实Java SDK签发并严格验签；合成秘密不对应任何部署。 */
class NativeCallTokensTest {

    private static final String SECRET = "synthetic-native-call-key-".repeat(3);
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final NativeCallTokens codec = new NativeCallTokens("synthetic-issuer", SECRET, CLOCK);
    private final String call = UUID.randomUUID().toString(),
        identity = UUID.randomUUID().toString();

    @Test
    void actualSdkTokenIsPrivateMicrophoneOnlyAndScopedToOriginalRandomIdentity() {
        var value = codec.issue(call, identity, "Synthetic caller");
        assertEquals(new NativeCallTokens.Join(call, identity), codec.verify(value, true));
        assertFalse(codec.verify(value, true).toString().contains(call));
        assertNull(new NativeCallTokens("other-issuer", SECRET, CLOCK).verify(value, true));
        assertNull(new NativeCallTokens("synthetic-issuer", SECRET + "x", CLOCK).verify(value, true));
    }

    @Test
    void expiredTokenCannotEnterButOriginalEstablishedProofStillRequiresSameSignature() {
        var token = codec.issue(call, identity, "Synthetic caller");
        var later = new NativeCallTokens("synthetic-issuer", SECRET, Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));
        assertNull(later.verify(token, true));
        assertNotNull(later.verify(token, false));
        assertNull(later.verify(token + "x", false));
    }

    @Test
    void futureTokenOverlongLifetimeAndMissingNotBeforeFailClosed() throws Exception {
        var token = codec.issue(call, identity, "Synthetic caller");
        for (String type : new String[] { "future", "long", "missing" }) {
            ObjectNode body = body(token);
            if (type.equals("future")) body.put("nbf", NOW.plusSeconds(1).getEpochSecond());
            else if (type.equals("long")) body.put("exp", NOW.plusSeconds(3601).getEpochSecond());
            else body.remove("nbf");
            assertNull(codec.verify(sign(header(token), body.toString()), false));
        }
    }

    @Test
    void publicRoomLegacyUserAndAnyAdditionalMediaCapabilityAreDeniedEvenWhenCorrectlySigned() throws Exception {
        var token = codec.issue(call, identity, "Synthetic caller");
        for (String type : new String[] { "public", "user", "data", "camera", "admin", "metadata", "sip" }) {
            var body = body(token);
            var grants = (ObjectNode) body.get("video");
            switch (type) {
                case "public" -> grants.put("room", "koko-voice-123");
                case "user" -> body.put("sub", "user-123");
                case "data" -> grants.put("canPublishData", true);
                case "camera" -> grants.putArray("canPublishSources").add("microphone").add("camera");
                case "admin" -> grants.put("roomAdmin", true);
                case "metadata" -> grants.put("canUpdateOwnMetadata", true);
                case "sip" -> body.putObject("sip").put("admin", true);
            }
            assertNull(codec.verify(sign(header(token), body.toString()), true), type);
        }
    }

    @Test
    void duplicateKeysRemoteKeyHeadersAndMalformedTokensAreRejectedWithoutDetails() throws Exception {
        var token = codec.issue(call, identity, "Synthetic caller");
        assertNull(codec.verify(sign("{\"alg\":\"HS256\",\"kid\":\"remote\"}", body(token).toString()), true));
        String original = body(token).toString();
        assertNull(codec.verify(sign(header(token), "{\"iss\":\"other\"," + original.substring(1)), true));
        assertNull(codec.verify(null, true));
        assertNull(codec.verify("x".repeat(8193), true));
        assertNull(codec.verify("a.b.c", true));
        assertNull(codec.verify(token + ".", true));
    }

    private ObjectNode body(String token) throws Exception {
        return (ObjectNode) new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private String header(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
    }

    private String sign(String header, String body) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String content =
            encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) +
            "." +
            encoder.encodeToString(body.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return content + "." + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }
}
