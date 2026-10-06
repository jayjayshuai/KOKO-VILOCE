package cn.kokonexus.gateway.infrastructure;

import cn.kokonexus.api.voice.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** 只转发准入，不将Cookie或LiveKit管理密钥发送给媒体服务；同步调用须由有界池执行。 */
@Component
public class MediaAdmissionClient {

    /** 单次有限等待，不重试携带JWT的鉴权RPC。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 2000, retries = 0)
    private MediaAdmissionRpcService service;

    public boolean admit(MediaAdmissionCommand command) {
        try {
            return service.admit(command);
        } catch (RuntimeException unavailable) {
            throw new MediaAdmissionUnavailableException();
        }
    }
}
