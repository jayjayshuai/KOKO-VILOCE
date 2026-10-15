package cn.kokonexus.chat.infrastructure.media;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.livekit.server.RoomServiceClient;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import retrofit2.Call;

/** 有限时间、零网络重试；404只有可信Twirp not_found才证明原目标不存在。 */
@Component
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class LiveKitNativeCallGateway implements NativeCallMediaGateway {

    /** 私有管理客户端，密码只在启动配置中读取。 */
    private final RoomServiceClient client;
    /** 浏览器同源信令基址。 */
    private final String publicUrl;
    /** 严格有界错误解析，不把代理HTML404视为清退成功。 */
    private final ObjectMapper json = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public LiveKitNativeCallGateway(
        @Value("${livekit.api-url:}") String apiUrl,
        @Value("${livekit.native-call-public-url:}") String publicUrl,
        @Value("${livekit.api-key:}") String key,
        @Value("${livekit.api-secret:}") String secret
    ) {
        if (
            key.isBlank() || secret.length() < 32 || apiUrl.isBlank() || publicUrl.isBlank()
        ) throw new IllegalStateException("私人音频配置不完整");
        validateEndpoint(apiUrl, false);
        validateEndpoint(publicUrl, true);
        this.publicUrl = publicUrl;
        this.client = RoomServiceClient.createClient(
            apiUrl,
            key,
            secret,
            () ->
                new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .callTimeout(Duration.ofSeconds(4))
                    .readTimeout(Duration.ofSeconds(4))
                    .writeTimeout(Duration.ofSeconds(4))
                    .retryOnConnectionFailure(false)
                    .build(),
            false
        );
    }

    @Override
    public void provision(String callId) {
        perform(client.createRoom(NativeCallTokens.room(callId), 60, 8), false);
    }

    @Override
    public void delete(String callId) {
        perform(client.deleteRoom(NativeCallTokens.room(callId)), true);
    }

    @Override
    public void remove(String callId, String identity) {
        if (
            identity == null || !UUID.fromString(identity).toString().equals(identity)
        ) throw new IllegalArgumentException("清退媒体身份无效");
        perform(client.removeParticipant(NativeCallTokens.room(callId), identity), true);
    }

    @Override
    public String publicUrl() {
        return publicUrl;
    }

    /** 配置出错时只给固定描述，拒绝URL中的嵌入密码/查询串和公网明文信令。 */
    private static void validateEndpoint(String value, boolean browser) {
        try {
            var uri = java.net.URI.create(value);
            boolean local = java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(
                uri.getHost() == null ? "" : uri.getHost()
            );
            boolean scheme = browser
                ? "wss".equals(uri.getScheme()) || (local && "ws".equals(uri.getScheme()))
                : "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
            boolean path = browser
                ? java.util.Set.of("/api/media/native-call/livekit", "/koko-api/media/native-call/livekit").contains(
                      uri.getRawPath()
                  )
                : uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath());
            if (
                uri.getHost() == null ||
                uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null ||
                uri.getPort() == 0 ||
                uri.getPort() > 65535 ||
                !scheme ||
                !path
            ) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalStateException("私人音频端点配置无效，须私有管理基址及受控安全信令入口");
        }
    }

    private void perform(Call<?> operation, boolean absentAllowed) {
        try {
            var response = operation.execute();
            if (response.isSuccessful()) return;
            if (response.errorBody() != null) try (var stream = response.errorBody().byteStream()) {
                byte[] bytes = stream.readNBytes(1025);
                if (absentAllowed && response.code() == 404 && bytes.length <= 1024) {
                    var error = json.readTree(bytes);
                    if (error != null && error.isObject() && "not_found".equals(error.path("code").asText())) return;
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            throw new cn.kokonexus.common.api.ExternalDependencyUnavailableException("私人音频服务暂不可用", null);
        }
        throw new cn.kokonexus.common.api.ExternalDependencyUnavailableException("私人音频操作未获确认", null);
    }
}
