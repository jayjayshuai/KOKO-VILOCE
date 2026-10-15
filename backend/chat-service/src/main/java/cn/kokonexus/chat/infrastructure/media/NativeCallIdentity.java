package cn.kokonexus.chat.infrastructure.media;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.voice.MediaAdmissionUnavailableException;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 私人音频身份二跳在SQL事务外执行；停用账号不靠前端隐藏。 */
@Component
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallIdentity {

    /** 只读身份RPC，一秒上限零重试。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 1000, retries = 0)
    private IdentityRpcService identity;

    public boolean active(long user) {
        if (user <= 0) return false;
        try {
            var found = identity.findActiveUser(String.valueOf(user));
            if (found == null) return false;
            if (!String.valueOf(user).equals(found.id())) throw new MediaAdmissionUnavailableException();
            return true;
        } catch (IllegalArgumentException denied) {
            return false;
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }
}
