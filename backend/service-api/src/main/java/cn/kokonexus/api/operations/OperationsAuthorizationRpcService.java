package cn.kokonexus.api.operations;

/** identity 持久授权契约；不能从客户端角色/头或注册顺序推导权力。 */
public interface OperationsAuthorizationRpcService {
    /** 读取指定认证账号当前有效的角色/权限；网络依赖失效不得返回允许。 */
    OperationsAccess access(String userId) throws OperationsAccessDeniedException;

    /** 管理员本人二次确认后受理角色变更，默认无自举管理员；目标不能为本人。 */
    OperationsRoleChangeReceipt changeRole(String operatorId, OperationsRoleChangeCommand command, String password)
        throws OperationsAccessDeniedException, OperationsRateLimitedException, OperationsUnavailableException;

    /** 业务域在执行受保护用例前再次读取事实权限。 */
    void requirePermission(String userId, String permission)
        throws OperationsAccessDeniedException, OperationsUnavailableException;

    /** 仅认证网关调用，本人密码确认生成会话/命令绑定凭据；不作为通用管理员票据。 */
    ReplayConfirmation confirmReplay(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String password
    ) throws OperationsAccessDeniedException, OperationsRateLimitedException, OperationsUnavailableException;

    /** 业务域验证摘要、到期、当前权限/账号/密码版本和完整命令绑定。 */
    void validateReplayConfirmation(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String confirmationToken
    ) throws OperationsAccessDeniedException, OperationsUnavailableException;
}
