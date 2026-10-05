package cn.kokonexus.community.infrastructure;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.stereotype.Component;

/** 入会的公开身份快照从内部目录取得，不能采用客户端伪造姓名或跨库读取账号。 */
@Component
public class CommunityIdentityDirectory {

    /** 身份 RPC 不重试，故障时不伪造身份或创建关系。 */
    @DubboReference(version = "1.0.0", check = false, retries = 0)
    private IdentityRpcService identity;

    public ChatIdentity byId(long userId) {
        try {
            var user = identity.findActiveUser(Long.toString(userId));
            return new ChatIdentity(user.id(), user.handle(), user.displayName());
        } catch (RpcException exception) {
            throw new ExternalDependencyUnavailableException("身份目录暂不可用，请稍后重试", exception);
        }
    }
}
