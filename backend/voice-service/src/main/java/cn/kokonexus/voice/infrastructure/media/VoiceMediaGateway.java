package cn.kokonexus.voice.infrastructure.media;

/** voice-service：VoiceMediaGateway 领域类型；字段单位、状态及可空性见各属性说明。 */
public interface VoiceMediaGateway {
    void provision(String roomName, int maxParticipants);
    String issueJoinToken(String roomName, long userId, String displayName);
    /** 只接受服务器已核验的随机绑定身份；听众不能发布，文本数据和元信息修改均禁止。 */
    String issueBoundJoinToken(String roomName, String identity, String displayName, boolean publish);
    void delete(String roomName);
    /** 固定原媒体身份退场；不使自建SFU的旧JWT自动失效，不能将其用作新权限授予。 */
    void removeParticipant(String roomName, String mediaIdentity);
    String publicUrl();
}
