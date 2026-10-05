package cn.kokonexus.community.interfaces;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.kokonexus.common.api.GlobalExceptionHandler;
import cn.kokonexus.community.application.CommunityMembershipService;
import cn.kokonexus.community.domain.Community;
import cn.kokonexus.community.domain.CommunityMember;
import cn.kokonexus.community.infrastructure.CommunityIdentityDirectory;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommunityMembershipControllerTest {

    private final CommunityMembershipService service = mock(CommunityMembershipService.class);
    private final CommunityIdentityDirectory directory = mock(CommunityIdentityDirectory.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new CommunityMembershipController(service, directory)
    )
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    void memberPaginationUsesStringIdsAndDoesNotExposeInternalEntityFields() throws Exception {
        var first = member(9007199254740993L);
        var extra = member(9007199254740992L);
        when(service.memberPage(42, 100, null, 1)).thenReturn(List.of(first, extra));
        mvc.perform(get("/api/communities/100/members?size=1").header("X-Koko-User-Id", "42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nextBefore").value("9007199254740993"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].userId").value("9007199254740993"))
            .andExpect(jsonPath("$.items[0].communityId").doesNotExist())
            .andExpect(jsonPath("$.items[0].email").doesNotExist());
    }

    @Test
    void joinedReturnsSafeProjectionAndMutationUsesTrustedHeader() throws Exception {
        var community = new Community();
        community.setId(100L);
        community.setMemberCount(1L);
        community.setVersion(0L);
        when(service.joined(42, null, 20)).thenReturn(List.of(community));
        mvc.perform(get("/api/communities/joined").header("X-Koko-User-Id", "42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("100"))
            .andExpect(jsonPath("$.items[0].ownerId").doesNotExist());
        mvc.perform(delete("/api/communities/100/members/43").header("X-Koko-User-Id", "42")).andExpect(
            status().isNoContent()
        );
        verify(service).remove(42, 100, 43);
    }

    private CommunityMember member(long userId) {
        var member = new CommunityMember();
        member.setUserId(userId);
        member.setRole("MEMBER");
        member.setJoinedAt(LocalDateTime.now());
        return member;
    }
}
