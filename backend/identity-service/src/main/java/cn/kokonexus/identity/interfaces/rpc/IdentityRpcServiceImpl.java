package cn.kokonexus.identity.interfaces.rpc;

import cn.kokonexus.api.identity.AuthenticateIdentityCommand;
import cn.kokonexus.api.identity.CreatorFollowState;
import cn.kokonexus.api.identity.CreatorPage;
import cn.kokonexus.api.identity.CreatorProfile;
import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.identity.RegisterIdentityCommand;
import cn.kokonexus.api.identity.SaveCreatorProfileCommand;
import cn.kokonexus.api.identity.UserIdentity;
import cn.kokonexus.identity.application.CreatorFollowApplicationService;
import cn.kokonexus.identity.application.CreatorProfileApplicationService;
import cn.kokonexus.identity.application.IdentityApplicationService;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboService;

/** identity-service：IdentityRpcServiceImpl 领域类型；字段单位、状态及可空性见各属性说明。 */
@DubboService(version = "1.0.0", timeout = 3000, retries = 0)
public class IdentityRpcServiceImpl implements IdentityRpcService {

    /** IdentityApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final IdentityApplicationService applicationService;
    /** CreatorProfileApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final CreatorProfileApplicationService creatorProfileService;
    /** CreatorFollowApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final CreatorFollowApplicationService creatorFollowService;

    public IdentityRpcServiceImpl(
        IdentityApplicationService applicationService,
        CreatorProfileApplicationService creatorProfileService,
        CreatorFollowApplicationService creatorFollowService
    ) {
        this.applicationService = applicationService;
        this.creatorProfileService = creatorProfileService;
        this.creatorFollowService = creatorFollowService;
    }

    @Override
    public UserIdentity register(RegisterIdentityCommand command) {
        return applicationService.register(command);
    }

    @Override
    public UserIdentity authenticate(AuthenticateIdentityCommand command) {
        return applicationService.authenticate(command);
    }

    @Override
    public UserIdentity findActiveUser(String userId) {
        return applicationService.findActiveUser(userId);
    }

    @Override
    public cn.kokonexus.api.identity.ChatIdentity findChatIdentity(String handle) {
        return applicationService.findChatIdentity(handle);
    }

    @Override
    public CreatorProfile findCreatorProfile(String userId) {
        return creatorProfileService.findMine(userId);
    }

    @Override
    public CreatorProfile findPublishedCreator(String slug) {
        return creatorProfileService.findPublished(slug);
    }

    @Override
    public boolean isPublishedProfileAsset(String assetId) {
        return creatorProfileService.isPublishedAsset(assetId);
    }

    @Override
    public boolean hasProfileAssetReference(String assetId) {
        return creatorProfileService.hasAssetReference(assetId);
    }

    @Override
    public List<CreatorProfile> listPublishedCreators(int limit) {
        return creatorProfileService.listPublished(limit);
    }

    @Override
    public CreatorPage pagePublishedCreators(int page, int size) {
        return creatorProfileService.pagePublished(page, size);
    }

    @Override
    public CreatorPage pageFollowedCreators(String userId, int page, int size) {
        return creatorFollowService.following(userId, page, size);
    }

    @Override
    public List<String> pageFollowerIds(String creatorId, String afterFollowerId, int size) {
        return creatorFollowService.followerIds(creatorId, afterFollowerId, size);
    }

    @Override
    public CreatorFollowState creatorFollowState(String userId, String creatorId) {
        return creatorFollowService.state(userId, creatorId);
    }

    @Override
    public CreatorFollowState followCreator(String userId, String creatorId) {
        return creatorFollowService.follow(userId, creatorId);
    }

    @Override
    public CreatorFollowState unfollowCreator(String userId, String creatorId) {
        return creatorFollowService.unfollow(userId, creatorId);
    }

    @Override
    public CreatorProfile saveCreatorProfile(String userId, SaveCreatorProfileCommand command) {
        return creatorProfileService.save(userId, command);
    }

    @Override
    public CreatorProfile publishCreatorProfile(String userId, long version) {
        return creatorProfileService.publish(userId, version);
    }
}
