package cn.kokonexus.api.asset;

/** 平台公共契约：业务用例；涉及写入时遵守领域事务与权限约束。 */
public interface AssetRpcService {
    /** A false result means missing, unfinished, foreign-owned or wrong-purpose. */
    boolean isOwnedReady(String ownerId, String assetId, String purpose);

    /** 在资产库事务中锁定资产并持久化绑定意图；请求 UUID 必须保持重试幂等，不自动过期。 */
    boolean beginBinding(String ownerId, String assetId, String purpose, String requestId);

    /** 仅在调用者本地事务明确提交或回滚后释放对应意图；重复完成无副作用。 */
    void completeBinding(String ownerId, String assetId, String purpose, String requestId);

    /** 原子写入永久结束标记并释放意图，阻止迟到 begin 复活；旧 Provider 不支持时必须失败关闭。 */
    void sealBinding(String ownerId, String assetId, String purpose, String requestId);
}
