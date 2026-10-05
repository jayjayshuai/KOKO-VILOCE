package cn.kokonexus.identity.application;

import cn.kokonexus.api.identity.CreatorFollowState;
import cn.kokonexus.api.identity.CreatorPage;
import cn.kokonexus.api.identity.CreatorProfileConflictException;
import cn.kokonexus.identity.domain.CreatorProfileEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.CreatorFollowMapper;
import cn.kokonexus.identity.infrastructure.persistence.CreatorProfileMapper;
import cn.kokonexus.identity.infrastructure.persistence.UserMapper;
import cn.kokonexus.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** identity-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class CreatorFollowApplicationService {

    /** CreatorFollowMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CreatorFollowMapper followMapper;
    /** CreatorProfileMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CreatorProfileMapper profileMapper;
    /** UserMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final UserMapper userMapper;
    /** CreatorProfileApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final CreatorProfileApplicationService profileService;
    /** OutboxWriter 外部或领域适配器，失败不伪装为业务成功。 */
    private final OutboxWriter outboxWriter;

    public CreatorFollowApplicationService(
        CreatorFollowMapper followMapper,
        CreatorProfileMapper profileMapper,
        UserMapper userMapper,
        CreatorProfileApplicationService profileService,
        OutboxWriter outboxWriter
    ) {
        this.followMapper = followMapper;
        this.profileMapper = profileMapper;
        this.userMapper = userMapper;
        this.profileService = profileService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public CreatorFollowState follow(String userId, String creatorId) {
        long follower = parseId(userId);
        long creator = parseId(creatorId);
        if (follower == creator) {
            throw new IllegalArgumentException("不能关注自己");
        }
        requireActiveUser(follower);
        requirePublished(creator);
        if (followMapper.insertFollow(creator, follower) == 1) {
            if (followMapper.incrementFollowerCount(creator) != 1) {
                throw new IllegalStateException("关注计数更新失败");
            }
            outboxWriter.enqueue(creator, follower, "FOLLOW", String.valueOf(creator), "有人关注了你");
        }
        CreatorFollowState result = state(creator, follower);
        if (!result.followedByMe()) throw new CreatorProfileConflictException("关注状态已变化，请重试");
        return result;
    }

    @Transactional
    public CreatorFollowState unfollow(String userId, String creatorId) {
        long follower = parseId(userId);
        long creator = parseId(creatorId);
        requireActiveUser(follower);
        if (followMapper.deleteFollow(creator, follower) == 1 && followMapper.decrementFollowerCount(creator) != 1) {
            throw new IllegalStateException("关注计数更新失败");
        }
        return state(creator, follower);
    }

    @Transactional(readOnly = true)
    public CreatorFollowState state(String userId, String creatorId) {
        return state(parseId(creatorId), parseId(userId));
    }

    @Transactional(readOnly = true)
    public CreatorPage following(String userId, int page, int size) {
        long follower = parseId(userId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        return new CreatorPage(
            followMapper
                .selectFollowedCreators(follower, (long) (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(profileService::toProfile)
                .toList(),
            safePage,
            safeSize,
            followMapper.countFollowedCreators(follower)
        );
    }

    @Transactional(readOnly = true)
    public java.util.List<String> followerIds(String creatorId, String afterFollowerId, int size) {
        long creator = parseId(creatorId);
        long after = afterFollowerId == null || afterFollowerId.isBlank() ? 0 : parseId(afterFollowerId);
        if (creator <= 0 || after < 0) throw new IllegalArgumentException("用户标识无效");
        return followMapper
            .selectFollowerIdsAfter(creator, after, Math.min(Math.max(size, 1), 100))
            .stream()
            .map(String::valueOf)
            .toList();
    }

    private CreatorFollowState state(long creator, long follower) {
        CreatorProfileEntity profile = profileMapper.selectById(creator);
        if (profile == null) {
            throw new CreatorProfileConflictException("创作者主页不存在");
        }
        return new CreatorFollowState(
            String.valueOf(creator),
            profile.getFollowerCount(),
            followMapper.hasFollow(creator, follower)
        );
    }

    private void requirePublished(long creator) {
        CreatorProfileEntity profile = profileMapper.selectById(creator);
        if (profile == null || !"ACTIVE".equals(profile.getStatus())) {
            throw new CreatorProfileConflictException("创作者主页不存在或尚未发布");
        }
    }

    private void requireActiveUser(long userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new CreatorProfileConflictException("账号当前不可用");
        }
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("用户标识无效", exception);
        }
    }
}
