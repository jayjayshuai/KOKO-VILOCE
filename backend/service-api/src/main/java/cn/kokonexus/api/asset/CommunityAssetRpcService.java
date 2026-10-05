package cn.kokonexus.api.asset;

/** 平台公共契约：业务用例；涉及写入时遵守领域事务与权限约束。 */
public interface CommunityAssetRpcService {
    /** Reads the community database's published-post reference, not a cached flag. */
    boolean isPublishedCover(String assetId);

    /** 查询所有状态的真实封面引用，包括草稿和归档；仅为快照，不授权物理删除。 */
    boolean hasCoverReference(String assetId);
}
