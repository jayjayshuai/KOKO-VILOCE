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
    /** 已结束网站会话的持久退场登记，不访问SFU。 */ private final cn.kokonexus.voice.application.VoiceWebsiteSessionRetirement retirement;

    public VoiceMediaAdmissionProvider(
        LiveKitJoinTokenVerifier verifier,
        MediaIdentityClient directory,
        VoiceMediaAdmissionState state,
        cn.kokonexus.voice.application.VoiceWebsiteSessionRetirement retirement,
        @Value("${koko.voice.signal-admission-enabled:false}") boolean enabled
    ) {
        this.verifier = verifier;
        this.directory = directory;
        this.state = state;
        this.retirement = retirement;
        this.enabled = enabled;
    }

    @Override
    public boolean retireWebsiteSession(MediaWebsiteSessionCommand command) {
        if (!enabled) throw new MediaAdmissionUnavailableException();
        try {
            return retirement.retire(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    @Override
    public boolean admit(MediaAdmissionCommand command) {
        return check(command, true);
    }

    @Override
    public boolean retain(MediaAdmissionCommand command) {
        return check(command, false);
    }

    private boolean check(MediaAdmissionCommand command, boolean entry) {
        if (!enabled) throw new MediaAdmissionUnavailableException();
        if (command == null) return false;
        var join = entry
            ? verifier.verify(command.token(), command.userId())
            : verifier.verifyRetained(command.token(), command.userId());
        if (join == null) return false;
        try {
            if (!directory.active(Long.parseLong(command.userId()))) {
                if (join.binding()) retirement.retire(
                    new MediaWebsiteSessionCommand(command.userId(), command.websiteScope())
                );
                return false;
            }
            return state.allows(join, Long.parseLong(command.userId()), entry, command.websiteScope());
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }
}
