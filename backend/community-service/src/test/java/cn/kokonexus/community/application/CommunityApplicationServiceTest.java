package cn.kokonexus.community.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.community.domain.Community;
import cn.kokonexus.community.domain.CommunityMember;
import cn.kokonexus.community.infrastructure.persistence.CommunityMapper;
import cn.kokonexus.community.infrastructure.persistence.CommunityMemberMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommunityApplicationServiceTest {

    @Test
    void createPersistsCommunityAndOwnerMembership() {
        CommunityMapper communityMapper = mock(CommunityMapper.class);
        CommunityMemberMapper memberMapper = mock(CommunityMemberMapper.class);
        when(communityMapper.insert(any(Community.class))).thenAnswer(invocation -> {
            Community community = invocation.getArgument(0);
            community.setId(2001L);
            return 1;
        });
        when(memberMapper.insert(any(CommunityMember.class))).thenReturn(1);

        Community created = new CommunityApplicationService(communityMapper, memberMapper).create(
            1001L,
            "creator-hub",
            " 创作者营地 ",
            "交流与协作",
            "up"
        );

        assertThat(created.getSlug()).isEqualTo("creator-hub");
        assertThat(created.getName()).isEqualTo("创作者营地");
        assertThat(created.getBadge()).isEqualTo("UP");
        ArgumentCaptor<CommunityMember> owner = ArgumentCaptor.forClass(CommunityMember.class);
        verify(memberMapper).insert(owner.capture());
        assertThat(owner.getValue().getUserId()).isEqualTo(1001L);
        assertThat(owner.getValue().getRole()).isEqualTo("OWNER");
    }

    @Test
    void updateUsesOwnerAndVersionGuard() {
        CommunityMapper communityMapper = mock(CommunityMapper.class);
        CommunityMemberMapper memberMapper = mock(CommunityMemberMapper.class);
        Community updated = community(2001L, 1001L, 4L, "ACTIVE");
        updated.setName("新的名称");
        when(communityMapper.updateOwned(2001L, 1001L, 3L, "新的名称", "介绍", "UP", "PRIVATE")).thenReturn(1);
        when(communityMapper.selectById(2001L)).thenReturn(updated);

        Community result = new CommunityApplicationService(communityMapper, memberMapper).update(
            1001L,
            2001L,
            3L,
            " 新的名称 ",
            "介绍",
            "up",
            "PRIVATE"
        );

        assertThat(result.getVersion()).isEqualTo(4L);
        assertThat(result.getName()).isEqualTo("新的名称");
    }

    @Test
    void archiveRejectsNonOwnerWithoutChangingState() {
        CommunityMapper communityMapper = mock(CommunityMapper.class);
        CommunityMemberMapper memberMapper = mock(CommunityMemberMapper.class);
        when(communityMapper.archiveOwned(2001L, 9999L, 3L)).thenReturn(0);
        when(communityMapper.selectById(2001L)).thenReturn(community(2001L, 1001L, 3L, "ACTIVE"));

        assertThatThrownBy(() ->
            new CommunityApplicationService(communityMapper, memberMapper).archive(9999L, 2001L, 3L)
        )
            .isInstanceOf(ForbiddenOperationException.class)
            .hasMessage("只有社区所有者可以执行此操作");
    }

    @Test
    void archiveRejectsStaleVersion() {
        CommunityMapper communityMapper = mock(CommunityMapper.class);
        CommunityMemberMapper memberMapper = mock(CommunityMemberMapper.class);
        when(communityMapper.archiveOwned(2001L, 1001L, 2L)).thenReturn(0);
        when(communityMapper.selectById(2001L)).thenReturn(community(2001L, 1001L, 3L, "ACTIVE"));

        assertThatThrownBy(() ->
            new CommunityApplicationService(communityMapper, memberMapper).archive(1001L, 2001L, 2L)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("社区已被其他操作更新，请刷新后重试");
    }

    private Community community(long id, long ownerId, long version, String status) {
        Community community = new Community();
        community.setId(id);
        community.setOwnerId(ownerId);
        community.setVersion(version);
        community.setStatus(status);
        return community;
    }
}
