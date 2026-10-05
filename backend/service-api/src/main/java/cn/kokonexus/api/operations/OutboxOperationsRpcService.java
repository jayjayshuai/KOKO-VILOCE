package cn.kokonexus.api.operations;

/** identity/community/live 固定 group 的本域运营契约；不接受客户端表名/业务域/操作者头。 */
public interface OutboxOperationsRpcService {
    /** 当前读权限检查后读取本域死信，不包含租约秘密。 */
    OutboxDeadPage dead(String operatorId, OutboxEventCursor cursor, int limit)
        throws OperationsAccessDeniedException, OperationsUnavailableException;
    /** 非 DEAD 也可读当前状态，以便区分重放已受理、已发送与消费完成。 */
    OutboxEventView detail(String operatorId, String eventId)
        throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
    /** 查询单事件审计，exclusive 代次分页。 */
    OutboxAuditPage audits(String operatorId, String eventId, Long beforeGeneration, int limit)
        throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
    /** 只读查询原受理命令；404 仅说明本次未观察到，不能证明其他在途请求不会提交。 */
    OutboxAuditView receipt(String operatorId, String eventId, String requestId)
        throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
    /** 会话/命令确认再次验证后同库重新排队；不在 RPC 内同步发送。 */
    OutboxReplayReceipt replay(
        String operatorId,
        String sessionHash,
        OutboxReplayCommand command,
        String confirmationToken
    ) throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
}
