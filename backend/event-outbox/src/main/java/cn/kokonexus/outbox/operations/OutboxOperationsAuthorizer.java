package cn.kokonexus.outbox.operations;

/** 当前有效权限检查边界；生产实现必须查服务端事实，不能依赖客户端角色或默认管理员。 */
@FunctionalInterface
public interface OutboxOperationsAuthorizer {
    /** 不允许时抛出权限异常，依赖不可用时拒绝，不能把异常转换为允许。 */
    void requirePermission(long operatorId, String permission);
}
