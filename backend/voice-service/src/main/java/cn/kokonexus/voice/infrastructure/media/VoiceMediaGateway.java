package cn.kokonexus.voice.infrastructure.media;

/** voice-service：VoiceMediaGateway 领域类型；字段单位、状态及可空性见各属性说明。 */
public interface VoiceMediaGateway {
    void provision(String roomName, int maxParticipants);
    String issueJoinToken(String roomName, long userId, String displayName);
    void delete(String roomName);
    String publicUrl();
}
