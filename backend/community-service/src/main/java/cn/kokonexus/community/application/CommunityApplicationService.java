package cn.kokonexus.community.application;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.community.domain.Community;
import cn.kokonexus.community.domain.CommunityMember;
import cn.kokonexus.community.infrastructure.persistence.CommunityMapper;
import cn.kokonexus.community.infrastructure.persistence.CommunityMemberMapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** community-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class CommunityApplicationService {

    /** CommunityMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CommunityMapper communityMapper;
    /** CommunityMemberMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CommunityMemberMapper memberMapper;

    public CommunityApplicationService(CommunityMapper communityMapper, CommunityMemberMapper memberMapper) {
        this.communityMapper = communityMapper;
        this.memberMapper = memberMapper;
    }

    @Transactional
    public Community create(long ownerId, String slug, String name, String description, String badge) {
        Community community = new Community();
        community.setOwnerId(ownerId);
        community.setSlug(slug.toLowerCase(Locale.ROOT));
        community.setName(name.trim());
        community.setDescription(description == null ? null : description.trim());
        community.setBadge(badge.toUpperCase(Locale.ROOT));
        community.setMemberCount(1L);
        community.setVisibility("PUBLIC");
        community.setStatus("ACTIVE");
        community.setVersion(0L);
        try {
            if (communityMapper.insert(community) != 1) {
                throw new IllegalStateException("社区创建失败");
            }
            CommunityMember owner = new CommunityMember();
            owner.setCommunityId(community.getId());
            owner.setUserId(ownerId);
            owner.setRole("OWNER");
            if (memberMapper.insert(owner) != 1) {
                throw new IllegalStateException("社区所有者写入失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("社区地址已被占用", exception);
        }
        return community;
    }

    @Transactional(readOnly = true)
    public List<Community> discover(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        Page<Community> page = Page.of(1, safeLimit, false);
        page.addOrder(OrderItem.desc("member_count"));
        return communityMapper
            .selectPage(
                page,
                Wrappers.<Community>lambdaQuery()
                    .eq(Community::getStatus, "ACTIVE")
                    .eq(Community::getVisibility, "PUBLIC")
            )
            .getRecords();
    }

    @Transactional(readOnly = true)
    public List<Community> ownedBy(long ownerId) {
        return communityMapper.selectList(
            Wrappers.<Community>lambdaQuery()
                .eq(Community::getOwnerId, ownerId)
                .eq(Community::getStatus, "ACTIVE")
                .orderByDesc(Community::getUpdatedAt, Community::getCreatedAt)
        );
    }

    @Transactional
    public Community update(
        long ownerId,
        long id,
        long expectedVersion,
        String name,
        String description,
        String badge,
        String visibility
    ) {
        String normalizedVisibility = visibility.toUpperCase(Locale.ROOT);
        if (
            communityMapper.updateOwned(
                id,
                ownerId,
                expectedVersion,
                name.trim(),
                description == null ? null : description.trim(),
                badge.toUpperCase(Locale.ROOT),
                normalizedVisibility
            ) != 1
        ) {
            explainRejectedMutation(ownerId, id, expectedVersion);
        }
        return communityMapper.selectById(id);
    }

    @Transactional
    public void archive(long ownerId, long id, long expectedVersion) {
        if (communityMapper.archiveOwned(id, ownerId, expectedVersion) != 1) {
            explainRejectedMutation(ownerId, id, expectedVersion);
        }
    }

    private void explainRejectedMutation(long ownerId, long id, long expectedVersion) {
        Community current = communityMapper.selectById(id);
        if (current == null) {
            throw new ResourceNotFoundException("社区不存在");
        }
        if (!current.getOwnerId().equals(ownerId)) {
            throw new ForbiddenOperationException("只有社区所有者可以执行此操作");
        }
        if (!"ACTIVE".equals(current.getStatus())) {
            throw new IllegalStateException("社区已归档");
        }
        if (!current.getVersion().equals(expectedVersion)) {
            throw new IllegalStateException("社区已被其他操作更新，请刷新后重试");
        }
        throw new IllegalStateException("社区状态已变化，请刷新后重试");
    }
}
