package cn.kokonexus.identity.infrastructure.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DiscordSecurityPrimitivesTest {

    @Test
    void materialIsRandomBoundedAndChallengeIsExactS256() throws Exception {
        var one = DiscordAuthorizationMaterial.generate();
        var two = DiscordAuthorizationMaterial.generate();
        assertNotEquals(one.getState(), two.getState());
        assertNotEquals(one.getVerifier(), two.getVerifier());
        assertEquals(43, one.getState().length());
        assertEquals(43, one.getVerifier().length());
        assertEquals(64, one.getStateDigest().length());
        assertEquals(one.getStateDigest(), DiscordAuthorizationMaterial.stateDigest(one.getState()));
        assertEquals(
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(one.getVerifier().getBytes(StandardCharsets.US_ASCII))
                ),
            one.getChallenge()
        );
        assertFalse(one.toString().contains(one.getVerifier()));
        assertFalse(one.toString().contains(one.getState()));
        assertFalse(
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(one).contains(one.getVerifier())
        );
    }

    @Test
    void malformedAndPlainPkceAreRejected() {
        for (String value : new String[] { "", "a".repeat(42), "a".repeat(129), "!".repeat(43), "é".repeat(43) })
            assertThrows(IllegalArgumentException.class, () -> DiscordAuthorizationMaterial.challenge(value));
        assertThrows(IllegalArgumentException.class, () -> DiscordAuthorizationMaterial.challenge(null));
        assertThrows(IllegalArgumentException.class, () -> DiscordAuthorizationMaterial.stateDigest("short"));
    }

    @Test
    void envelopeHasRandomNonceAndRoundTripsWithoutPlaintext() {
        var cipher = cipher();
        String value = UUID.randomUUID().toString();
        String one = cipher.encrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, value);
        String two = cipher.encrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, value);
        assertNotEquals(one, two);
        assertFalse(one.contains(value));
        assertEquals(value, cipher.decrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, one));
    }

    @Test
    void wrongOwnerPurposeKeyOrTamperingFailsClosedWithSafeError() {
        var cipher = cipher();
        String value = UUID.randomUUID().toString();
        String one = cipher.encrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, value);
        var failure = assertThrows(IllegalStateException.class, () ->
            cipher.decrypt("124", DiscordTokenCipher.Purpose.REFRESH_TOKEN, one)
        );
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(one));
        assertThrows(IllegalStateException.class, () ->
            cipher.decrypt("123", DiscordTokenCipher.Purpose.OAUTH_VERIFIER, one)
        );
        assertThrows(IllegalStateException.class, () ->
            cipher().decrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, one)
        );
        String[] parts = one.split("\\.");
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[3]);
        ciphertext[0] ^= 1;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        assertThrows(IllegalStateException.class, () ->
            cipher.decrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, String.join(".", parts))
        );
    }

    @Test
    void rotationReadsOldButNewWritesUseNewKeyAndDefensiveCopies() {
        byte[] old = key(),
            next = key();
        String value = UUID.randomUUID().toString();
        var original = new DiscordTokenCipher("old", Map.of("old", old));
        String encrypted = original.encrypt("123", DiscordTokenCipher.Purpose.OAUTH_VERIFIER, value);
        Map<String, byte[]> supplied = new HashMap<>(Map.of("old", old, "new", next));
        var rotated = new DiscordTokenCipher("new", supplied);
        old[0] ^= 1;
        next[0] ^= 1;
        supplied.clear();
        assertEquals(value, rotated.decrypt("123", DiscordTokenCipher.Purpose.OAUTH_VERIFIER, encrypted));
        String newEnvelope = rotated.encrypt("123", DiscordTokenCipher.Purpose.OAUTH_VERIFIER, value);
        assertTrue(newEnvelope.startsWith("v1.new."));
        assertEquals(value, rotated.decrypt("123", DiscordTokenCipher.Purpose.OAUTH_VERIFIER, newEnvelope));
        assertThrows(IllegalStateException.class, () ->
            original.decrypt("123", DiscordTokenCipher.Purpose.OAUTH_VERIFIER, newEnvelope)
        );
    }

    @Test
    void envelopeAndContextBoundsRejectBeforeCrypto() {
        var cipher = cipher();
        for (String owner : new String[] { "0", "01", "-1", "1:REFRESH_TOKEN", "x" })
            assertThrows(IllegalArgumentException.class, () ->
                cipher.encrypt(owner, DiscordTokenCipher.Purpose.REFRESH_TOKEN, "value")
            );
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt("1", null, "value"));
        assertThrows(IllegalArgumentException.class, () ->
            cipher.encrypt("1", DiscordTokenCipher.Purpose.REFRESH_TOKEN, "é".repeat(2049))
        );
        for (String envelope : new String[] { "", "v0.old.a.a", "v1.absent.a.a", "a".repeat(8193) })
            assertThrows(IllegalStateException.class, () ->
                cipher.decrypt("1", DiscordTokenCipher.Purpose.REFRESH_TOKEN, envelope)
            );
        assertThrows(IllegalStateException.class, () ->
            cipher.decrypt("1", DiscordTokenCipher.Purpose.REFRESH_TOKEN, null)
        );
        assertThrows(IllegalArgumentException.class, () -> new DiscordTokenCipher("bad", Map.of("bad", new byte[31])));
    }

    @Test
    void enabledConfigurationRequiresFixedHttpsAndPrivateKeyButDisabledNeedsNoCredentials() {
        var disabled = new DiscordOAuthSettings();
        assertDoesNotThrow(disabled::validate);
        for (String callback : new String[] {
            "http://app.example.invalid/",
            "https://a.example.invalid/?code=x",
            "https://a.example.invalid/#/account",
            "https://user:secret@a.example.invalid/",
            "https://a.example.invalid:444/",
            "malformed",
        }) {
            var settings = settings();
            settings.setRedirectUri(callback);
            var error = assertThrows(IllegalStateException.class, settings::validate);
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains(callback));
        }
        var invalid = settings();
        invalid.setEncryptionKey("");
        assertThrows(IllegalStateException.class, invalid::validate);
        var duplicate = settings();
        duplicate.setPreviousEncryptionKeys(Map.of(duplicate.getEncryptionKeyId(), duplicate.getEncryptionKey()));
        assertThrows(IllegalStateException.class, duplicate::validate);
        assertDoesNotThrow(settings()::validate);
    }

    @Test
    void springDisabledBuildsNoClientOrCipherAndEnabledMissingConfigFails() {
        new ApplicationContextRunner().withUserConfiguration(DiscordOAuthConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(DiscordOAuthClient.class).size());
            assertEquals(0, context.getBeansOfType(DiscordTokenCipher.class).size());
        });
        new ApplicationContextRunner()
            .withUserConfiguration(DiscordOAuthConfiguration.class)
            .withPropertyValues("koko.discord.enabled=true")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void springEnabledValidConfigurationCreatesOnlyServerSideAdapters() {
        var settings = settings();
        new ApplicationContextRunner()
            .withUserConfiguration(DiscordOAuthConfiguration.class)
            .withPropertyValues(
                "koko.discord.enabled=true",
                "koko.discord.client-id=" + settings.getClientId(),
                "koko.discord.client-secret=" + settings.getClientSecret(),
                "koko.discord.redirect-uri=" + settings.getRedirectUri(),
                "koko.discord.encryption-key-id=" + settings.getEncryptionKeyId(),
                "koko.discord.encryption-key=" + settings.getEncryptionKey()
            )
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeansOfType(DiscordOAuthClient.class).size());
                assertEquals(1, context.getBeansOfType(DiscordTokenCipher.class).size());
                String value = UUID.randomUUID().toString();
                var cipher = context.getBean(DiscordTokenCipher.class);
                String protectedValue = cipher.encrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, value);
                assertEquals(value, cipher.decrypt("123", DiscordTokenCipher.Purpose.REFRESH_TOKEN, protectedValue));
            });
    }

    @Test
    void authorizationHasOnlyIdentifyFixedCallbackAndNoVerifierOrSecret() throws Exception {
        var settings = settings();
        var client = new DiscordOAuthClient(settings);
        var material = DiscordAuthorizationMaterial.generate();
        var url = client.authorizationUri(material.getState(), material.getChallenge());
        Map<String, String> query = decode(url.getRawQuery());
        assertEquals("https", url.getScheme());
        assertEquals("discord.com", url.getHost());
        assertEquals("/oauth2/authorize", url.getPath());
        assertEquals("identify", query.get("scope"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals(material.getChallenge(), query.get("code_challenge"));
        assertEquals(material.getState(), query.get("state"));
        assertEquals(settings.getRedirectUri(), query.get("redirect_uri"));
        assertFalse(url.toString().contains(material.getVerifier()));
        assertFalse(url.toString().contains(settings.getClientSecret()));
        assertFalse(query.containsKey("client_secret"));
        assertFalse(query.containsKey("code_verifier"));
        assertFalse(settings.toString().contains(settings.getClientSecret()));
        String serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(settings);
        assertFalse(serialized.contains(settings.getClientSecret()));
        assertFalse(serialized.contains(settings.getEncryptionKey()));
    }

    static byte[] key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static DiscordTokenCipher cipher() {
        return new DiscordTokenCipher("current", Map.of("current", key()));
    }

    static DiscordOAuthSettings settings() {
        var settings = new DiscordOAuthSettings();
        settings.setEnabled(true);
        settings.setClientId("123456789012345678");
        settings.setClientSecret(UUID.randomUUID().toString());
        settings.setRedirectUri("https://app.example.invalid/koko/");
        settings.setEncryptionKeyId("current");
        settings.setEncryptionKey(Base64.getEncoder().encodeToString(key()));
        return settings;
    }

    static Map<String, String> decode(String query) {
        Map<String, String> result = new HashMap<>();
        for (String entry : query.split("&")) {
            String[] part = entry.split("=", 2);
            result.put(
                URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                URLDecoder.decode(part[1], StandardCharsets.UTF_8)
            );
        }
        return result;
    }
}
