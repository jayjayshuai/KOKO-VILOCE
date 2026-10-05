package cn.kokonexus.voice.infrastructure.media;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
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

    public LiveKitVoiceMediaGateway(
        @Value("${livekit.api-url}") String apiUrl,
        @Value("${livekit.public-url}") String publicUrl,
        @Value("${livekit.api-key}") String apiKey,
        @Value("${livekit.api-secret}") String apiSecret
    ) {
        if (apiKey.isBlank() || apiSecret.length() < 32 || publicUrl.isBlank()) {
            throw new IllegalStateException("LiveKit 生产配置不完整");
        }
        this.roomClient = RoomServiceClient.createClient(apiUrl, apiKey, apiSecret);
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
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity("user-" + userId);
        token.setName(displayName);
        token.setTtl(10 * 60 * 1000L);
        token.addGrants(
            new RoomJoin(true),
            new RoomName(roomName),
            new CanSubscribe(true),
            new CanPublish(true),
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
}
