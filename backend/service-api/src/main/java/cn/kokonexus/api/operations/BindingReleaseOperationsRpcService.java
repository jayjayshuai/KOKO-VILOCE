package cn.kokonexus.api.operations;

/** 仅 identity/community 的绑定释放排障；无删除资产、清除未知意图或通知重放能力。 */
public interface BindingReleaseOperationsRpcService {
    /** 当前事实权限校验后读取 DEAD 游标页，最多五十条。 */
    BindingReleasePage dead(String operatorId, BindingReleaseCursor cursor, int limit)
        throws OperationsAccessDeniedException, OperationsUnavailableException;
    /** 已排队或已确认任务也可查当前事实，不存在时明确返回未找到。 */
    BindingReleaseView detail(String operatorId, String requestId)
        throws OperationsAccessDeniedException, OperationsUnavailableException, OperationsNotFoundException;
    /** 返回同一 SQL 时点有界状态采样；依赖故障必须拒绝。 */
    BindingReleaseSnapshot snapshot(String operatorId)
        throws OperationsAccessDeniedException, OperationsUnavailableException;
}
