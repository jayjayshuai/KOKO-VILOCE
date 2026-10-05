package cn.kokonexus.community.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.community.domain.Community;
import cn.kokonexus.community.domain.CommunityMember;
import cn.kokonexus.community.infrastructure.persistence.CommunityMapper;
import cn.kokonexus.community.infrastructure.persistence.CommunityMemberMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommunityMembershipServiceTest {

    private final CommunityMapper communities = mock(CommunityMapper.class);
    private final CommunityMemberMapper members = mock(CommunityMemberMapper.class);
    private final CommunityMembershipService service = new CommunityMembershipService(communities, members);
    private Community community;

    @BeforeEach
    void setup() {
        community = new Community();
        community.setId(100L);
        community.setOwnerId(42L);
        community.setMemberCount(1L);
        community.setStatus("ACTIVE");
        community.setVisibility("PUBLIC");
        when(communities.lock(100)).thenReturn(community);
    }

    @Test
    void joinBindsDirectoryIdentityAndChangesCountOnce() {
        when(members.insert(any(CommunityMember.class))).thenReturn(1);
        when(communities.changeMembers(100, 1)).thenReturn(1);
        service.join(43, 100, identity(43));
        verify(members).insert(
            argThat(
                (CommunityMember value) ->
                    value.getUserId() == 43 && "MEMBER".equals(value.getRole()) && "member43".equals(value.getHandle())
            )
        );
        verify(communities).changeMembers(100, 1);
    }

    @Test
    void repeatJoinEvenAtCapacityDoesNotInsertOrIncrement() {
        community.setMemberCount(1000L);
        when(members.current(100, 43)).thenReturn(new CommunityMember());
        service.join(43, 100, identity(43));
        verify(members, never()).insert(any(CommunityMember.class));
        verify(communities, never()).changeMembers(anyLong(), anyInt());
    }

    @Test
    void invalidIdentityPrivateAndCapacityRefuseNewMembership() {
        assertThrows(IllegalArgumentException.class, () -> service.join(43, 100, identity(44)));
        community.setVisibility("PRIVATE");
        assertThrows(ResourceNotFoundException.class, () -> service.join(43, 100, identity(43)));
        assertThrows(ResourceNotFoundException.class, () -> service.status(43, 100));
        community.setVisibility("PUBLIC");
        community.setMemberCount(1000L);
        assertThrows(IllegalStateException.class, () -> service.join(43, 100, identity(43)));
        verify(members, never()).insert(any(CommunityMember.class));
    }

    @Test
    void ownerCannotLeaveOrBeRemovedAndNonOwnerCannotManage() {
        assertThrows(IllegalStateException.class, () -> service.leave(42, 100));
        assertThrows(IllegalStateException.class, () -> service.remove(42, 100, 42));
        assertThrows(ForbiddenOperationException.class, () -> service.remove(43, 100, 44));
        verify(members, never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void absentLeaveAndRemoveDoNotDecrement() {
        when(members.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);
        service.leave(43, 100);
        service.remove(42, 100, 44);
        community.setStatus("ARCHIVED");
        service.leave(43, 100);
        service.leave(43, 101);
        verify(communities, never()).changeMembers(anyLong(), anyInt());
    }

    @Test
    void actualRemovalChecksAffectedCountAndArchivedQueriesReject() {
        when(members.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(communities.changeMembers(100, -1)).thenReturn(1);
        service.remove(42, 100, 43);
        verify(communities).changeMembers(100, -1);
        community.setStatus("ARCHIVED");
        assertThrows(ResourceNotFoundException.class, () -> service.memberPage(42, 100, null, 20));
        assertThrows(ResourceNotFoundException.class, () -> service.join(43, 100, identity(43)));
    }

    @Test
    void pageBoundsAndCurrentMembershipPrecedeQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.joined(42, 0L, 20));
        assertThrows(IllegalArgumentException.class, () -> service.memberPage(42, 100, null, 51));
        assertThrows(ResourceNotFoundException.class, () -> service.memberPage(43, 100, null, 20));
        verify(members, never()).page(anyLong(), any(), anyInt());
        var owner = new CommunityMember();
        owner.setRole("OWNER");
        when(members.current(100, 42)).thenReturn(owner);
        when(members.page(100, 99L, 21)).thenReturn(List.of(owner));
        assertEquals(1, service.memberPage(42, 100, 99L, 20).size());
    }

    @Test
    void failedCounterUpdateThrowsInsteadOfFalseSuccess() {
        when(members.insert(any(CommunityMember.class))).thenReturn(1);
        when(communities.changeMembers(100, 1)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.join(43, 100, identity(43)));
    }

    private ChatIdentity identity(long id) {
        return new ChatIdentity(Long.toString(id), "member" + id, "Member " + id);
    }
}
