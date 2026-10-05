package cn.kokonexus.api.identity;

import java.util.List;

/** 平台公共契约：业务用例；涉及写入时遵守领域事务与权限约束。 */
public interface IdentityRpcService {
    UserIdentity register(RegisterIdentityCommand command);

    UserIdentity authenticate(AuthenticateIdentityCommand command);

    UserIdentity findActiveUser(String userId);

    /** 按公开用户名精确查询可用聊天身份，不对调用者返回邮箱。 */
    ChatIdentity findChatIdentity(String handle);

    CreatorProfile findCreatorProfile(String userId);

    CreatorProfile findPublishedCreator(String slug);

    boolean isPublishedProfileAsset(String assetId);

    /** 查询所有状态主页的头像/封面引用，包括草稿和停用；不能替代公开读取授权。 */
    boolean hasProfileAssetReference(String assetId);

    List<CreatorProfile> listPublishedCreators(int limit);

    CreatorPage pagePublishedCreators(int page, int size);

    CreatorPage pageFollowedCreators(String userId, int page, int size);

    List<String> pageFollowerIds(String creatorId, String afterFollowerId, int size);

    CreatorFollowState creatorFollowState(String userId, String creatorId);

    CreatorFollowState followCreator(String userId, String creatorId) throws CreatorProfileConflictException;

    CreatorFollowState unfollowCreator(String userId, String creatorId) throws CreatorProfileConflictException;

    CreatorProfile saveCreatorProfile(String userId, SaveCreatorProfileCommand command)
        throws CreatorProfileConflictException;

    CreatorProfile publishCreatorProfile(String userId, long version) throws CreatorProfileConflictException;
}
