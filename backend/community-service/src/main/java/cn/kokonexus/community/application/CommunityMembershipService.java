package cn.kokonexus.community.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.community.domain.Community;
import cn.kokonexus.community.domain.CommunityMember;
import cn.kokonexus.community.infrastructure.persistence.CommunityMapper;
import cn.kokonexus.community.infrastructure.persistence.CommunityMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 社区成员用例：社区行锁先于关系操作，人数/版本与成员事实同事务。 */
@Service
@RequiredArgsConstructor
public class CommunityMembershipService {

    /** 社区人数上限是保护配置，不代表服务器容量验收。 */
    private static final int MEMBER_LIMIT = 1000;
    /** 社区锁、人数 CAS 和本人列表。 */
    private final CommunityMapper communities;
    /** 当前成员事实，主键为社区/用户联合键。 */
    private final CommunityMemberMapper members;

    /** 公开社区允许查看入会状态；私密社区仅向当前成员返回元信息。 */
    @Transactional(timeout = 3)
    public Status status(long userId, long id) {
        var community = active(id);
        var member = member(id, userId);
        if (member == null && !"PUBLIC".equals(community.getVisibility())) {
            throw hidden();
        }
        return new Status(community, member == null ? null : member.getRole());
    }

    /** 加入只采用目录身份；已存在关系先返回原事实，不消耗人数或版本。 */
    @Transactional(timeout = 3)
    public void join(long userId, long id, ChatIdentity identity) {
        if (
            identity == null ||
            !Long.toString(userId).equals(identity.id()) ||
            identity.handle() == null ||
            identity.handle().isBlank() ||
            identity.handle().length() > 32 ||
            identity.displayName() == null ||
            identity.displayName().isBlank() ||
            identity.displayName().length() > 80
        ) {
            throw new IllegalArgumentException("入会身份无效");
        }
        var community = active(id);
        if (member(id, userId) != null) {
            return;
        }
        if (!"PUBLIC".equals(community.getVisibility())) {
            throw hidden();
        }
        if (community.getMemberCount() >= MEMBER_LIMIT) {
            throw new IllegalStateException("社区已达到 1000 人上限");
        }
        CommunityMember newMember = new CommunityMember();
        newMember.setCommunityId(id);
        newMember.setUserId(userId);
        newMember.setRole("MEMBER");
        newMember.setHandle(identity.handle());
        newMember.setDisplayName(identity.displayName());
        if (members.insert(newMember) != 1 || communities.changeMembers(id, 1) != 1) {
            throw new IllegalStateException("社区入会失败");
        }
    }

    /** 未加入/已归档/不存在均无副作用；所有者不能退出仍活跃的社区。 */
    @Transactional(timeout = 3)
    public void leave(long userId, long id) {
        positive(id);
        var community = communities.lock(id);
        if (community == null || !"ACTIVE".equals(community.getStatus())) {
            return;
        }
        if (community.getOwnerId() == userId) {
            throw new IllegalStateException("所有者不能退出社区，可在管理台归档社区");
        }
        removeRelationship(userId, id);
    }

    /** 所有者移除成员；不是永久封禁，公开社区中的用户可重新加入。 */
    @Transactional(timeout = 3)
    public void remove(long ownerId, long id, long targetId) {
        positive(targetId);
        var community = active(id);
        if (community.getOwnerId() != ownerId) {
            if (!"PUBLIC".equals(community.getVisibility()) && member(id, ownerId) == null) {
                throw hidden();
            }
            throw new ForbiddenOperationException("只有社区所有者可以移除成员");
        }
        if (targetId == ownerId) {
            throw new IllegalStateException("不能移除社区所有者");
        }
        removeRelationship(targetId, id);
    }

    /** 成员名册仅当前成员可读，按用户 ID 倒序游标，不计算无界总数。 */
    @Transactional(timeout = 3)
    public List<CommunityMember> memberPage(long userId, long id, Long before, int size) {
        bounds(before, size);
        active(id);
        if (member(id, userId) == null) {
            throw hidden();
        }
        return List.copyOf(members.page(id, before, size + 1));
    }

    /** 查本人 ACTIVE 成员关系；归档社区和已退出的关系不会返回。 */
    @Transactional(readOnly = true, timeout = 3)
    public List<Community> joined(long userId, Long before, int size) {
        bounds(before, size);
        return List.copyOf(communities.joined(userId, before, size + 1));
    }

    private void removeRelationship(long userId, long id) {
        int changed = members.delete(
            Wrappers.<CommunityMember>lambdaQuery()
                .eq(CommunityMember::getCommunityId, id)
                .eq(CommunityMember::getUserId, userId)
        );
        if (changed < 0 || changed > 1 || (changed == 1 && communities.changeMembers(id, -1) != 1)) {
            throw new IllegalStateException("社区成员移除失败");
        }
    }

    private Community active(long id) {
        positive(id);
        var community = communities.lock(id);
        if (community == null || !"ACTIVE".equals(community.getStatus())) {
            throw hidden();
        }
        return community;
    }

    private CommunityMember member(long id, long userId) {
        return members.current(id, userId);
    }

    private static void bounds(Long before, int size) {
        if (size < 1 || size > 50 || (before != null && before < 1)) {
            throw new IllegalArgumentException("社区分页参数无效，单页 1～50 条");
        }
    }

    private static void positive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("社区或成员 ID 必须为正整数");
        }
    }

    private static ResourceNotFoundException hidden() {
        return new ResourceNotFoundException("社区不存在或无访问权限");
    }

    /** 内部状态，HTTP 层只转换明确的公开/本人投影。 */
    public record Status(
        @Schema(description = "内部已授权社区事实，不能直接 HTTP 序列化") Community community,
        @Schema(description = "本人角色 OWNER/MEMBER；未加入为 null") String role
    ) {}
}
