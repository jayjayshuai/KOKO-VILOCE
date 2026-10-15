package cn.kokonexus.gateway.infrastructure;

import cn.kokonexus.api.voice.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** 只转发准入，不将Cookie或LiveKit管理密钥发送给媒体服务；同步调用须由有界池执行。 */
@Component
public class MediaAdmissionClient {

    /** 同一RPC超时/零重试；未完整登记不得冒充注销媒体已清退。 */
    public boolean retireWebsiteSession(MediaWebsiteSessionCommand command) {
        try {
            return service.retireWebsiteSession(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    /** 单次有限等待，不重试携带JWT的鉴权RPC。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 2000, retries = 0)
    private MediaAdmissionRpcService service;

    /** 私人通话使用独立RPC，不回退成公开语音房准入。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 2000, retries = 0)
    private cn.kokonexus.api.chat.NativeCallMediaAdmissionRpcService nativeCalls;

    public boolean admitNative(MediaAdmissionCommand command) {
        try {
            return nativeCalls.admit(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    public boolean retainNative(MediaAdmissionCommand command) {
        try {
            return nativeCalls.retain(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    public boolean retireNativeWebsiteSession(MediaWebsiteSessionCommand command) {
        try {
            return nativeCalls.retireWebsiteSession(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    public boolean admit(MediaAdmissionCommand command) {
        try {
            return service.admit(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }

    /** 持续核验仍受同一RPC超时/零重试约束，不把未实现方法当作允许。 */
    public boolean retain(MediaAdmissionCommand command) {
        try {
            return service.retain(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }
}
