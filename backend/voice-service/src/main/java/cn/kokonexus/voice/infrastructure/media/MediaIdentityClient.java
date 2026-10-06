package cn.kokonexus.voice.infrastructure.media;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.voice.MediaAdmissionUnavailableException;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** 准入二跳只读身份确认；未找到/停用与网络/损坏返回区分，不读取他域数据库。 */
@Component
public class MediaIdentityClient {

    /** 一秒上限/零重试，不能让媒体握手占用无限身份RPC工作时间。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 1000, retries = 0)
    private IdentityRpcService identity;

    public boolean active(long user) {
        if (user <= 0) return false;
        try {
            var found = identity.findActiveUser(Long.toString(user));
            if (found == null) return false;
            if (!Long.toString(user).equals(found.id())) throw new MediaAdmissionUnavailableException();
            return true;
        } catch (IllegalArgumentException denied) {
            return false;
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }
}
