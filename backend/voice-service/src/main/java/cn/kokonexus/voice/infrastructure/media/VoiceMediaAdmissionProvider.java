package cn.kokonexus.voice.infrastructure.media;

import cn.kokonexus.api.voice.*;
import cn.kokonexus.voice.application.VoiceMediaAdmissionState;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;

/** 信令RPC准入：签名/当前用户→身份目录→短事务房间事实，任何依赖故障拒绝开放。 */
@DubboService(version = "1.0.0")
public class VoiceMediaAdmissionProvider implements MediaAdmissionRpcService {

    /** 签名只在voice域核验，网关不持有LiveKit管理密钥。 */ private final LiveKitJoinTokenVerifier verifier;
    /** 当前Active身份目录，在SQL事务之外调用。 */ private final MediaIdentityClient directory;
    /** 当前锁定房间状态的事务代理。 */ private final VoiceMediaAdmissionState state;
    /** 新信令入口需分批配置并复验，默认不开放。 */ private final boolean enabled;

    public VoiceMediaAdmissionProvider(
        LiveKitJoinTokenVerifier verifier,
        MediaIdentityClient directory,
        VoiceMediaAdmissionState state,
        @Value("${koko.voice.signal-admission-enabled:false}") boolean enabled
    ) {
        this.verifier = verifier;
        this.directory = directory;
        this.state = state;
        this.enabled = enabled;
    }

    @Override
    public boolean admit(MediaAdmissionCommand command) {
        if (!enabled) throw new MediaAdmissionUnavailableException();
        if (command == null) return false;
        var join = verifier.verify(command.token(), command.userId());
        if (join == null) return false;
        try {
            if (!directory.active(Long.parseLong(command.userId()))) return false;
            return state.allows(join);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }
}
