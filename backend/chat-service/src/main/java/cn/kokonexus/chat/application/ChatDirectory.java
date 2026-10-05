package cn.kokonexus.chat.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.api.identity.IdentityRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/** 跨服务身份目录；仅内部 RPC，不跨库读取账号表。 */
@Component
public class ChatDirectory {

    /** 身份微服务，禁止重试注册等有副作用的方法。 */
    @DubboReference(version = "1.0.0", check = false, retries = 0)
    private IdentityRpcService identity;

    public ChatIdentity byHandle(String handle) {
        return identity.findChatIdentity(handle);
    }

    public ChatIdentity byId(long userId) {
        var user = identity.findActiveUser(Long.toString(userId));
        return new ChatIdentity(user.id(), user.handle(), user.displayName());
    }
}
