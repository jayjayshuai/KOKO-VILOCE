package cn.kokonexus.voice.application;

import cn.kokonexus.common.api.*;
import cn.kokonexus.voice.infrastructure.media.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 身份RPC→短事务绑定投影→本地JWT签名；不签旧user-ID发布令牌。 */
@Service
public class VoiceMediaCredentialIssuer {

    /** 独立候选开关，默认false，不证明SFU/公网已验收。 */ private final boolean enabled;
    /** 当前Active身份，在SQL事务之前核验。 */ private final MediaIdentityClient identity;
    /** 房间锁下的真实SQL授权事务代理。 */ private final VoiceMediaCredentialState state;
    /** 限房间UUID/麦克风的SDK签发适配器。 */ private final VoiceMediaGateway media;

    public VoiceMediaCredentialIssuer(
        MediaIdentityClient identity,
        VoiceMediaCredentialState state,
        VoiceMediaGateway media,
        @Value("${koko.voice.media-credentials-enabled:false}") boolean enabled
    ) {
        this.identity = identity;
        this.state = state;
        this.media = media;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Credential issue(long user, long roomId, String session, String version) {
        if (!enabled) throw new ExternalDependencyUnavailableException("受控媒体凭据尚未开放", null);
        try {
            if (!identity.active(user)) throw new ForbiddenOperationException("当前账号不可用");
        } catch (cn.kokonexus.api.voice.MediaAdmissionUnavailableException unavailable) {
            throw new ExternalDependencyUnavailableException("媒体身份核验暂不可用", null);
        }
        VoiceMediaCredentialState.Grant grant;
        try {
            grant = state.current(roomId, user, session, version);
        } catch (
            org.springframework.dao.DataAccessException
            | org.springframework.transaction.TransactionException unavailable
        ) {
            throw new ExternalDependencyUnavailableException("当前媒体授权读取暂不可用", null);
        }
        String token = media.issueBoundJoinToken(grant.roomName(), grant.identity(), grant.name(), grant.publish());
        return new Credential(
            media.publicUrl(),
            token,
            grant.roomName(),
            grant.sessionId(),
            grant.generation(),
            grant.seatNo(),
            grant.publish(),
            120
        );
    }

    /** 凭据只留客户端内存，禁止日志/序列化到持久存储。 */
    public record Credential(
        @io.swagger.v3.oas.annotations.media.Schema(description = "同源Gateway信令基址，正式环境WSS") String url,
        @io.swagger.v3.oas.annotations.media.Schema(description = "短期入会JWT，禁止日志和持久化") String token,
        @io.swagger.v3.oas.annotations.media.Schema(description = "供应商房间，不含PII") String roomName,
        @io.swagger.v3.oas.annotations.media.Schema(description = "本次当前成员会话，须与请求相同") String sessionId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前授权轮次字符串，禁转number") String generation,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前本人麦位，听众为空", nullable = true)
        Integer seatNo,
        @io.swagger.v3.oas.annotations.media.Schema(description = "仅麦克风发布权限，不等于麦克风已开启")
        boolean canPublish,
        @io.swagger.v3.oas.annotations.media.Schema(description = "初次入会有效期秒；已连接仍持续核验网站/成员/绑定")
        int expiresInSeconds
    ) {
        @Override
        public String toString() {
            return "ControlledMediaCredential[redacted]";
        }
    }
}
