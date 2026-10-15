package cn.kokonexus.identity.infrastructure.discord;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 实际JDK HTTP传输的环回验收；不是Discord供应商授权验收。 */
class DiscordOAuthHttpTest {

    private HttpServer server;
    private ExecutorService executor;
    private DiscordOAuthClient client;
    private DiscordOAuthSettings settings;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile byte[] response;
    private volatile boolean slow;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws IOException {
        settings = DiscordSecurityPrimitivesTest.settings();
        response = validToken();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        URI root = URI.create("http://localhost:" + server.getAddress().getPort() + "/");
        client = new DiscordOAuthClient(
            settings,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            root
        );
    }

    @AfterEach
    void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void realExchangeUsesFormPkceFixedCallbackAndHidesTokensInToString() throws IOException {
        String code = UUID.randomUUID().toString();
        var material = DiscordAuthorizationMaterial.generate();
        var token = client.exchange(code, material.getVerifier());
        Map<String, String> sent = DiscordSecurityPrimitivesTest.decode(received.get());
        assertEquals("authorization_code", sent.get("grant_type"));
        assertEquals(code, sent.get("code"));
        assertEquals(material.getVerifier(), sent.get("code_verifier"));
        assertEquals(settings.getClientSecret(), sent.get("client_secret"));
        assertEquals(settings.getRedirectUri(), sent.get("redirect_uri"));
        assertEquals(604800, token.getExpiresInSeconds());
        assertEquals(1, calls.get());
        assertFalse(token.toString().contains(token.getAccessToken()));
        assertFalse(token.toString().contains(token.getRefreshToken()));
        String serialized = json.writeValueAsString(token);
        assertFalse(serialized.contains(token.getAccessToken()));
        assertFalse(serialized.contains(token.getRefreshToken()));
    }

    @Test
    void realRefreshAndRevokeHaveExplicitFormsAndNoAutomaticRetries() {
        String value = UUID.randomUUID().toString();
        client.refresh(value);
        assertEquals(value, DiscordSecurityPrimitivesTest.decode(received.get()).get("refresh_token"));
        status = 204;
        response = new byte[0];
        client.revoke(value);
        var sent = DiscordSecurityPrimitivesTest.decode(received.get());
        assertEquals(value, sent.get("token"));
        assertEquals("refresh_token", sent.get("token_type_hint"));
        assertEquals(2, calls.get());
    }

    @Test
    void realUserReadReturnsOnlyIdentityAndValidatesBearerHeader() throws IOException {
        String value = UUID.randomUUID().toString();
        response = json.writeValueAsBytes(
            Map.of("id", "123456789012345678", "username", "name", "email", "ignored@example.invalid")
        );
        var user = client.currentUser(value);
        assertEquals("name", user.username());
        assertEquals("123456789012345678", user.id());
        assertEquals("Bearer " + value, authorization.get());
        assertEquals(1, calls.get());
        int before = calls.get();
        assertThrows(IllegalArgumentException.class, () -> client.currentUser(value + "\r\nInjected: yes"));
        assertEquals(before, calls.get());
    }

    @Test
    void actualRedirectDoesNotForwardCredentialOrFetchLocation() {
        status = 302;
        response = new byte[0];
        var error = assertThrows(DiscordOAuthFailure.class, () -> client.refresh(UUID.randomUUID().toString()));
        assertEquals(DiscordOAuthFailure.Kind.INVALID_RESPONSE, error.kind());
        assertEquals(1, calls.get());
    }

    @Test
    void actualErrorClassificationHasNoBodyOrCauseAndNeverRetries() {
        String privateMarker = UUID.randomUUID().toString();
        response = privateMarker.getBytes(StandardCharsets.UTF_8);
        for (int code : new int[] { 400, 401, 403, 429, 500, 503 }) {
            status = code;
            int before = calls.get();
            var error = assertThrows(DiscordOAuthFailure.class, () -> client.refresh(UUID.randomUUID().toString()));
            assertEquals(
                code < 429 ? DiscordOAuthFailure.Kind.DENIED : DiscordOAuthFailure.Kind.UNAVAILABLE,
                error.kind()
            );
            assertFalse(error.toString().contains(privateMarker));
            assertNull(error.getCause());
            assertEquals(before + 1, calls.get());
        }
    }

    @Test
    void malformedJsonDuplicateKeysWrongScopesAndTypesAreNotSuccessfulTokens() throws IOException {
        var base = json.readTree(validToken());
        for (String content : new String[] {
            "<html>unavailable</html>",
            "null",
            "[]",
            base.toString() + "{}",
            "{\"access_token\":\"a\",\"access_token\":\"b\"}",
            base.toString().replace("identify", "identify guilds"),
            base.toString().replace("604800", "\"604800\""),
            base.toString().replace("Bearer", "Other"),
            base.toString().replace("604800", "0"),
            base.toString().replace("604800", "999999999999999999999999999999"),
        }) {
            response = content.getBytes(StandardCharsets.UTF_8);
            assertEquals(
                DiscordOAuthFailure.Kind.INVALID_RESPONSE,
                assertThrows(DiscordOAuthFailure.class, () -> client.refresh(UUID.randomUUID().toString())).kind()
            );
        }
    }

    @Test
    void oversizedRealResponseIsCancelledAndCannotBecomeSuccess() {
        response = new byte[DiscordOAuthClient.BODY_LIMIT + 1];
        var error = assertThrows(DiscordOAuthFailure.class, () -> client.refresh(UUID.randomUUID().toString()));
        assertEquals(DiscordOAuthFailure.Kind.INVALID_RESPONSE, error.kind());
        assertEquals(1, calls.get());
        assertNull(error.getCause());
    }

    @Test
    void stalledRealBodyHasTotalTimeoutAndNoReplay() {
        slow = true;
        long start = System.nanoTime();
        var error = assertThrows(DiscordOAuthFailure.class, () -> client.refresh(UUID.randomUUID().toString()));
        assertEquals(DiscordOAuthFailure.Kind.UNAVAILABLE, error.kind());
        assertEquals(1, calls.get());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 12000);
    }

    @Test
    void invalidCodeVerifierTokenUserAndRedirectingClientAreRejected() throws IOException {
        assertThrows(IllegalArgumentException.class, () ->
            client.exchange("", DiscordAuthorizationMaterial.generate().getVerifier())
        );
        assertThrows(IllegalArgumentException.class, () -> client.exchange(UUID.randomUUID().toString(), "short"));
        assertThrows(IllegalArgumentException.class, () -> client.revoke("x".repeat(4097)));
        assertEquals(0, calls.get());
        response = json.writeValueAsBytes(Map.of("id", "wrong", "username", "name"));
        assertThrows(DiscordOAuthFailure.class, () -> client.currentUser(UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () ->
            new DiscordOAuthClient(
                settings,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
                URI.create("http://localhost/")
            )
        );
    }

    private byte[] validToken() throws IOException {
        return json.writeValueAsBytes(
            Map.of(
                "access_token",
                UUID.randomUUID().toString(),
                "refresh_token",
                UUID.randomUUID().toString(),
                "token_type",
                "Bearer",
                "scope",
                "identify",
                "expires_in",
                604800
            )
        );
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/credential-leak");
            if (slow) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            exchange.sendResponseHeaders(status, status == 204 ? -1 : response.length);
            if (status != 204) exchange.getResponseBody().write(response);
        }
    }
}
