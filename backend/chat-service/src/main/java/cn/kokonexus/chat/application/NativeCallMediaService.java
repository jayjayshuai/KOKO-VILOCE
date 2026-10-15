package cn.kokonexus.chat.application;

import cn.kokonexus.chat.infrastructure.media.*;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import cn.kokonexus.common.api.ForbiddenOperationException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 身份RPC与SFU调用均在SQL事务外；网络准备后再次核对，旧授权不能签出新凭据。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallMediaService {

    /** 当前同意事实的事务代理。 */ private final NativeCallService calls;
    /** 一秒当前账号确认，不使用跨域数据库。 */ private final NativeCallIdentity identity;
    /** 本地签名/严格验签。 */ private final NativeCallTokens tokens;
    /** 四秒有限媒体管理操作。 */ private final NativeCallMediaGateway media;

    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
    )
    public Credential issue(long user, String scope, String callId) {
        try {
            if (!identity.active(user)) throw new ForbiddenOperationException("当前账号不可用");
            var initial = calls.mediaGrant(user, scope, callId);
            media.provision(callId);
            var current = calls.mediaGrant(user, scope, callId);
            if (!current.equals(initial)) throw new ForbiddenOperationException("通话授权已变化");
            return new Credential(
                callId,
                media.publicUrl(),
                tokens.issue(callId, current.identity(), current.displayName()),
                60
            );
        } catch (
            cn.kokonexus.api.voice.MediaAdmissionUnavailableException
            | org.springframework.dao.DataAccessException
            | org.springframework.transaction.TransactionException unavailable
        ) {
            throw new ExternalDependencyUnavailableException("私人通话授权暂不可用", null);
        }
    }

    /** 凭据仅留客户端内存，返回并不表示麦克风已经采集或对方已收到声音。 */
    public record Credential(
        @Schema(description = "原通话UUID") String callId,
        @Schema(description = "同源受控WSS信令基址") String url,
        @Schema(description = "一分钟初次入会凭据，禁止日志和持久化") String token,
        @Schema(description = "初次入会有效期秒；持续连接另行核验") int expiresInSeconds
    ) {
        @Override
        public String toString() {
            return "NativeCallCredential[redacted]";
        }
    }
}
