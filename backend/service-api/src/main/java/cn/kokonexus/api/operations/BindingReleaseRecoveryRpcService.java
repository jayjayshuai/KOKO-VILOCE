package cn.kokonexus.api.operations;

/** 固定提交域恢复：查询需资产读权限，受理需资产恢复和命令绑定二次确认。 */
public interface BindingReleaseRecoveryRpcService {
    BindingReleaseReplayReceipt replay(
        String actor,
        String sessionHash,
        BindingReleaseReplayCommand command,
        String token
    ) throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
    BindingReleaseAuditView receipt(String actor, String requestId, String commandId)
        throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
    BindingReleaseAuditPage audits(String actor, String requestId, Integer beforeGeneration, int limit)
        throws OperationsAccessDeniedException, OperationsUnavailableException;
}
