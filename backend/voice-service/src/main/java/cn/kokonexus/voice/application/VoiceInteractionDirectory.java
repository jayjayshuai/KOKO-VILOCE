package cn.kokonexus.voice.application;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** 入房前从身份域确认名称，不在持有房间SQL锁时调用RPC。 */
@Component
public class VoiceInteractionDirectory {

    /** 已有身份契约，不直接跨库；不自动重试外部依赖。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 5000, retries = 0)
    private IdentityRpcService identity;

    public String name(long user) {
        if (user <= 0) throw new IllegalArgumentException("身份无效");
        try {
            var found = identity.findActiveUser(Long.toString(user));
            if (
                found == null ||
                !Long.toString(user).equals(found.id()) ||
                found.displayName() == null ||
                found.displayName().isBlank() ||
                found.displayName().length() > 80
            ) throw new IllegalStateException("身份目录返回无效");
            return found.displayName();
        } catch (RuntimeException failure) {
            throw new ExternalDependencyUnavailableException("成员身份确认暂不可用", failure);
        }
    }
}
