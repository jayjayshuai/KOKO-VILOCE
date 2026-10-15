package cn.kokonexus.identity.infrastructure.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.Getter;

/** 固定Discord端点、有界正文/超时、禁止重定向/自动重试的服务端适配器。 */
public final class DiscordOAuthClient {

    /** 非业务可配置URL，避免生产配置被用作SSRF或向任意站点发送令牌。 */
    private static final URI API = URI.create("https://discord.com/api/v10/");
    /** 仅固定官方授权端点允许打开浏览器；不接受客户端returnUrl。 */
    private static final String AUTHORIZE = "https://discord.com/oauth2/authorize";
    /** 供应商单次调用最长8秒；上层不得自动重放未知结果。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    /** 用户资料和令牌响应均不应超过32KiB，不先下载无限正文后裁剪。 */
    static final int BODY_LIMIT = 32 * 1024;
    /** 不跟随重定向的HTTP客户端。 */
    private final HttpClient http;
    /** 只在包内隔离HTTP测试替换为环回服务器，生产入口固定API。 */
    private final URI api;
    /** 构造时复制的应用标识，不随外部对象变化。 */
    private final String clientId;
    /** 服务端应用密钥，无toString或序列化访问器。 */
    private final String clientSecret;
    /** 本次应用固定的回调，交换时与授权时相同。 */
    private final String redirectUri;
    /** 独立JSON解析器，不保存外部响应到日志。 */
    private final ObjectMapper json = new ObjectMapper().enable(
        com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION
    );

    public DiscordOAuthClient(DiscordOAuthSettings settings) {
        this(
            settings,
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            API
        );
    }

    DiscordOAuthClient(DiscordOAuthSettings settings, HttpClient http, URI api) {
        settings.validate();
        if (!settings.isEnabled()) throw new IllegalStateException("Discord authorization disabled");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException(
            "Discord HTTP redirects must be disabled"
        );
        this.http = http;
        this.api = api;
        this.clientId = settings.getClientId();
        this.clientSecret = settings.getClientSecret();
        this.redirectUri = settings.getRedirectUri();
    }

    /** 仅申请identify，不申请邮箱/机器人/群权限；nonce的会话绑定和消费须由持久化用例完成。 */
    public URI authorizationUri(String state, String challenge) {
        if (
            state == null ||
            !state.matches("[A-Za-z0-9_-]{43}") ||
            challenge == null ||
            !challenge.matches("[A-Za-z0-9_-]{43}")
        ) throw new IllegalArgumentException("Invalid Discord authorization material");
        return URI.create(
            AUTHORIZE +
                "?" +
                form(
                    Map.of(
                        "client_id",
                        clientId,
                        "response_type",
                        "code",
                        "redirect_uri",
                        redirectUri,
                        "scope",
                        "identify",
                        "state",
                        state,
                        "prompt",
                        "consent",
                        "code_challenge",
                        challenge,
                        "code_challenge_method",
                        "S256"
                    )
                )
        );
    }

    /** 一次交换，不自动重试；超时不能判断授权码有没有被供应商消费。 */
    public Token exchange(String code, String verifier) {
        opaque(code);
        DiscordAuthorizationMaterial.challenge(verifier);
        return token(
            post(
                "oauth2/token",
                Map.of(
                    "grant_type",
                    "authorization_code",
                    "code",
                    code,
                    "code_verifier",
                    verifier,
                    "redirect_uri",
                    redirectUri,
                    "client_id",
                    clientId,
                    "client_secret",
                    clientSecret
                )
            )
        );
    }

    /** 刷新可能轮换令牌；调用方必须持久保护新旧轮次，不盲目网络重试。 */
    public Token refresh(String refreshToken) {
        opaque(refreshToken);
        return token(
            post(
                "oauth2/token",
                Map.of(
                    "grant_type",
                    "refresh_token",
                    "refresh_token",
                    refreshToken,
                    "client_id",
                    clientId,
                    "client_secret",
                    clientSecret
                )
            )
        );
    }

    /** 撤销整个用户应用授权；只有收到成功状态才确认外部撤销完成。 */
    public void revoke(String refreshToken) {
        opaque(refreshToken);
        execute(
            HttpRequest.newBuilder(api.resolve("oauth2/token/revoke"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form(
                            Map.of(
                                "token",
                                refreshToken,
                                "token_type_hint",
                                "refresh_token",
                                "client_id",
                                clientId,
                                "client_secret",
                                clientSecret
                            )
                        )
                    )
                )
                .timeout(TIMEOUT)
                .build()
        );
    }

    /** 不取邮箱；验证标识/用户名形状，不凭外部任意对象判定身份。 */
    public User currentUser(String accessToken) {
        opaque(accessToken);
        JsonNode value = parse(
            execute(
                HttpRequest.newBuilder(api.resolve("users/@me"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build()
            )
        );
        String id = text(value, "id"),
            username = text(value, "username");
        if (
            !id.matches("[1-9][0-9]{16,19}") ||
            username.isBlank() ||
            username.length() > 80 ||
            username.chars().anyMatch(Character::isISOControl)
        ) throw invalidResponse();
        return new User(id, username);
    }

    private byte[] post(String path, Map<String, String> parameters) {
        return execute(
            HttpRequest.newBuilder(api.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters)))
                .build()
        );
    }

    private byte[] execute(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 400 || status == 401 || status == 403) throw new DiscordOAuthFailure(
                DiscordOAuthFailure.Kind.DENIED
            );
            if (status == 429 || status >= 500) throw new DiscordOAuthFailure(DiscordOAuthFailure.Kind.UNAVAILABLE);
            if (status < 200 || status >= 300) throw invalidResponse();
            return response.body();
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DiscordOAuthFailure(DiscordOAuthFailure.Kind.UNAVAILABLE);
        } catch (TimeoutException unavailable) {
            pending.cancel(true);
            throw new DiscordOAuthFailure(DiscordOAuthFailure.Kind.UNAVAILABLE);
        } catch (ExecutionException unavailable) {
            pending.cancel(true);
            if (unavailable.getCause() instanceof DiscordOAuthFailure failure) throw new DiscordOAuthFailure(
                failure.kind()
            );
            throw new DiscordOAuthFailure(DiscordOAuthFailure.Kind.UNAVAILABLE);
        }
    }

    private Token token(byte[] body) {
        JsonNode value = parse(body);
        String access = text(value, "access_token"),
            refresh = text(value, "refresh_token");
        if (
            !"Bearer".equalsIgnoreCase(text(value, "token_type")) ||
            !"identify".equals(text(value, "scope")) ||
            !value.path("expires_in").isIntegralNumber() ||
            !value.path("expires_in").canConvertToLong()
        ) throw invalidResponse();
        long seconds = value.path("expires_in").longValue();
        if (
            seconds < 1 || seconds > 31_536_000 || !validOpaque(access) || !validOpaque(refresh)
        ) throw invalidResponse();
        return new Token(access, refresh, seconds);
    }

    private JsonNode parse(byte[] body) {
        try {
            // 拒绝尾随第二个对象，不能从畸形响应读取第一个“成功”对象。
            JsonNode result = json
                .reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(body);
            if (result == null || !result.isObject()) throw invalidResponse();
            return result;
        } catch (java.io.IOException malformed) {
            throw invalidResponse();
        }
    }

    private static String text(JsonNode value, String key) {
        if (!value.path(key).isTextual()) throw invalidResponse();
        return value.path(key).textValue();
    }

    private static String form(Map<String, String> values) {
        return values
            .entrySet()
            .stream()
            .map(
                entry ->
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) +
                    "=" +
                    URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)
            )
            .collect(Collectors.joining("&"));
    }

    private static boolean validOpaque(String value) {
        return (
            value != null &&
            !value.isEmpty() &&
            value.length() <= 4096 &&
            value.chars().allMatch(c -> c >= 33 && c <= 126)
        );
    }

    private static void opaque(String value) {
        if (!validOpaque(value)) throw new IllegalArgumentException("Invalid Discord credential shape");
    }

    private static DiscordOAuthFailure invalidResponse() {
        return new DiscordOAuthFailure(DiscordOAuthFailure.Kind.INVALID_RESPONSE);
    }

    /** 不使用record/Data，防止默认toString把两个令牌写入日志。 */
    @Getter
    public static final class Token {

        /** 只用于服务器调用，不进入客户端响应。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private final String accessToken;

        /** 持久化时需认证加密，不能在日志输出。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private final String refreshToken;

        /** 供应商确认的剩余有效秒数，不是无限使用保证。 */
        private final long expiresInSeconds;

        private Token(String accessToken, String refreshToken, long expiresInSeconds) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    /** 最小外部用户投影，不包含邮箱、刷新令牌或整个供应商响应。 */
    public record User(
        @io.swagger.v3.oas.annotations.media.Schema(description = "已通过格式校验的Discord用户标识") String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Discord公开用户名，不是本地登录凭据") String username
    ) {}

    /** 读取即设上限，超过时取消订阅；慢流由execute总时限取消。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        /** 有界正文结果，异常不保留原响应。 */
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        /** 只积累最多BODY_LIMIT字节。 */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /** 当前上游订阅，失败时立即取消剩余正文。 */
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > BODY_LIMIT - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(invalidResponse());
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] next = new byte[buffer.remaining()];
                buffer.get(next);
                bytes.writeBytes(next);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable ignored) {
            body.completeExceptionally(new DiscordOAuthFailure(DiscordOAuthFailure.Kind.UNAVAILABLE));
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
