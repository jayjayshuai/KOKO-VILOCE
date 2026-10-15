package cn.kokonexus.chat.infrastructure.media;

import cn.kokonexus.api.chat.NativeCallMediaAdmissionRpcService;
import cn.kokonexus.api.voice.*;
import cn.kokonexus.chat.application.NativeCallService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

/** 独立私人信令准入RPC；默认关，未启用时不创建媒体密钥/客户端或轮询工作线程。 */
@DubboService(version = "1.0.0")
public class NativeCallMediaAdmissionProvider implements NativeCallMediaAdmissionRpcService {

    /** 开关不能由客户端请求覆盖。 */ private final boolean enabled;
    /** 可选服务只在候选开启时取得，不伪造禁用环境的媒体就绪。 */ private final ObjectProvider<NativeCallService> calls;
    /** 本地域签名器，不与公开房间验证器混用。 */ private final ObjectProvider<NativeCallTokens> tokens;
    /** 当前账号停用/注销不应继续媒体。 */ private final ObjectProvider<NativeCallIdentity> identity;

    public NativeCallMediaAdmissionProvider(
        @Value("${koko.chat.calls-enabled:false}") boolean enabled,
        ObjectProvider<NativeCallService> calls,
        ObjectProvider<NativeCallTokens> tokens,
        ObjectProvider<NativeCallIdentity> identity
    ) {
        this.enabled = enabled;
        this.calls = calls;
        this.tokens = tokens;
        this.identity = identity;
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
        if (
            command == null || !validUser(command.userId()) || !WebsiteSessionScope.valid(command.websiteScope())
        ) return false;
        var join = tokens.getObject().verify(command.token(), entry);
        if (join == null) return false;
        try {
            long user = Long.parseLong(command.userId());
            if (!identity.getObject().active(user)) {
                calls.getObject().retireWebsite(user, command.websiteScope());
                return false;
            }
            return calls.getObject().mediaAllows(user, command.websiteScope(), join.callId(), join.identity());
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    @Override
    public boolean retireWebsiteSession(MediaWebsiteSessionCommand command) {
        if (!enabled) throw new MediaAdmissionUnavailableException();
        if (
            command == null || !validUser(command.userId()) || !WebsiteSessionScope.valid(command.websiteScope())
        ) return false;
        try {
            return calls.getObject().retireWebsite(Long.parseLong(command.userId()), command.websiteScope());
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    private static boolean validUser(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
