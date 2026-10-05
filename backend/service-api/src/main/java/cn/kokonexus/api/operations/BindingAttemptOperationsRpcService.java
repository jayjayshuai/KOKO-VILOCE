package cn.kokonexus.api.operations;

/** 固定写域凭据只读契约，无任意核对/清理/造结束证明接口。 */
public interface BindingAttemptOperationsRpcService {
    /** asset:binding:read 当前事实授权，按创建时点/UUID升序，最多50。 */
    BindingAttemptPage open(String operatorId, BindingReleaseCursor cursor, int limit)
        throws OperationsAccessDeniedException, OperationsUnavailableException;
    /** 没有新协议凭据明确404，不返回“回滚”假事实。 */
    BindingAttemptView detail(String operatorId, String requestId)
        throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
}
