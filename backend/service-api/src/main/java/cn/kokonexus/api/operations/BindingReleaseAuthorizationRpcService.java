package cn.kokonexus.api.operations;

/** 独立资产恢复动作，不接受通知确认令牌或客户端权限名。 */
public interface BindingReleaseAuthorizationRpcService {
    ReplayConfirmation confirm(
        String actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String password
    ) throws OperationsAccessDeniedException, OperationsRateLimitedException, OperationsUnavailableException;
    void validate(String actor, String sessionHash, String domain, BindingReleaseReplayCommand command, String token)
        throws OperationsAccessDeniedException, OperationsUnavailableException;
}
