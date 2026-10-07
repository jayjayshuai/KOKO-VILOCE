package cn.kokonexus.voice.infrastructure.media;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.CanUpdateOwnMetadata;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Response;

/** voice-service：LiveKitVoiceMediaGateway 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class LiveKitVoiceMediaGateway implements VoiceMediaGateway {

    /** RoomServiceClient 业务用例依赖，事务由 Spring 代理管理。 */
    private final RoomServiceClient roomClient;
    /** 供应商 API 标识，禁止日志输出。 */
    private final String apiKey;
    /** 供应商密钥，禁止序列化和日志输出。 */
    private final String apiSecret;
    /** 客户端可访问的媒体服务地址。 */
    private final String publicUrl;
    /** Twirp故障只读取有界严格JSON，不相信HTML404或重复code键。 */
    private final com.fasterxml.jackson.databind.ObjectMapper errors = new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public LiveKitVoiceMediaGateway(
        @Value("${livekit.api-url}") String apiUrl,
        @Value("${livekit.public-url}") String publicUrl,
        @Value("${livekit.api-key}") String apiKey,
        @Value("${livekit.api-secret}") String apiSecret
    ) {
        if (apiKey.isBlank() || apiSecret.length() < 32 || publicUrl.isBlank()) {
            throw new IllegalStateException("LiveKit 生产配置不完整");
        }
        this.roomClient = RoomServiceClient.createClient(
            apiUrl,
            apiKey,
            apiSecret,
            () ->
                new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .callTimeout(java.time.Duration.ofSeconds(5))
                    .readTimeout(java.time.Duration.ofSeconds(5))
                    .writeTimeout(java.time.Duration.ofSeconds(5))
                    .retryOnConnectionFailure(false)
                    .build(),
            false
        );
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.publicUrl = publicUrl;
    }

    @Override
    public void provision(String roomName, int maxParticipants) {
        try {
            Response<?> response = roomClient.createRoom(roomName, 300, maxParticipants).execute();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("LiveKit 创建房间失败，状态码 " + response.code());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("LiveKit 当前不可用", exception);
        }
    }

    @Override
    public String issueJoinToken(String roomName, long userId, String displayName) {
        return token(roomName, "user-" + userId, displayName, true, 600_000L);
    }

    @Override
    public String issueBoundJoinToken(String roomName, String identity, String displayName, boolean publish) {
        if (
            roomName == null ||
            !roomName.matches("koko-voice-[1-9][0-9]{0,18}") ||
            identity == null ||
            !java.util.UUID.fromString(identity).toString().equals(identity) ||
            displayName == null ||
            displayName.isBlank() ||
            displayName.length() > 80
        ) throw new IllegalArgumentException("受控媒体签发事实无效");
        try {
            if (Long.parseLong(roomName.substring("koko-voice-".length())) <= 0) throw new IllegalArgumentException(
                "房间无效"
            );
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("媒体房间标识超界");
        }
        return token(roomName, identity, displayName, publish, 120_000L);
    }

    /** 签名是本地纯计算，不调用SFU；新轮次初次入会期限两分钟，持续授权另由Gateway核验。 */
    private String token(String roomName, String identity, String displayName, boolean publish, long ttl) {
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(identity);
        token.setName(displayName);
        token.setTtl(ttl);
        // SDK默认未写nbf；明确写同一时刻的nbf/exp供持续连接核验签名期限，避免时间差溢出。
        var issuedAt = java.time.Instant.now();
        token.setNotBefore(java.util.Date.from(issuedAt));
        token.setExpiration(java.util.Date.from(issuedAt.plusMillis(ttl)));
        token.addGrants(
            new RoomJoin(true),
            new RoomName(roomName),
            new CanSubscribe(true),
            new CanPublish(publish),
            new CanPublishData(false),
            new CanUpdateOwnMetadata(false),
            new CanPublishSources(List.of("microphone"))
        );
        return token.toJwt();
    }

    @Override
    public void delete(String roomName) {
        try {
            Response<Void> response = roomClient.deleteRoom(roomName).execute();
            if (!response.isSuccessful() && response.code() != 404) {
                throw new IllegalStateException("LiveKit 关闭房间失败，状态码 " + response.code());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("LiveKit 当前不可用", exception);
        }
    }

    @Override
    public String publicUrl() {
        return publicUrl;
    }

    /** 只有可信Twirp not_found才当作原参与者已不在；HTML404/坏路径不伪装任务完成。 */
    @Override
    public void removeParticipant(String roomName, String mediaIdentity) {
        if (
            roomName == null ||
            !roomName.matches("koko-voice-[1-9][0-9]{0,18}") ||
            mediaIdentity == null ||
            !java.util.UUID.fromString(mediaIdentity).toString().equals(mediaIdentity)
        ) throw new IllegalArgumentException("媒体退场目标无效");
        try {
            if (Long.parseLong(roomName.substring("koko-voice-".length())) <= 0) throw new IllegalArgumentException(
                "房间无效"
            );
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("媒体退场房间超界");
        }
        try {
            Response<Void> response = roomClient.removeParticipant(roomName, mediaIdentity).execute();
            if (response.isSuccessful()) return;
            if (response.code() == 404 && response.errorBody() != null) {
                try (var stream = response.errorBody().byteStream()) {
                    byte[] bytes = stream.readNBytes(1025);
                    if (bytes.length <= 1024) {
                        var error = errors.readTree(bytes);
                        if (
                            error != null &&
                            error.isObject() &&
                            error.path("code").isTextual() &&
                            "not_found".equals(error.path("code").textValue())
                        ) return;
                    }
                }
            }
            throw new IllegalStateException("LiveKit参与者退场未确认");
        } catch (IOException failure) {
            throw new IllegalStateException("LiveKit参与者退场暂不可用");
        }
    }
}
