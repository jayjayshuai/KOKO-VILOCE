package cn.kokonexus.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.identity.CreatorProfileConflictException;
import cn.kokonexus.identity.domain.CreatorProfileEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.AssetBindingValidator;
import cn.kokonexus.identity.infrastructure.persistence.CreatorFollowMapper;
import cn.kokonexus.identity.infrastructure.persistence.CreatorProfileMapper;
import cn.kokonexus.identity.infrastructure.persistence.UserMapper;
import cn.kokonexus.outbox.OutboxWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreatorFollowApplicationServiceTest {

    private final CreatorFollowMapper followMapper = mock(CreatorFollowMapper.class);
    private final CreatorProfileMapper profileMapper = mock(CreatorProfileMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final CreatorProfileApplicationService profileService = new CreatorProfileApplicationService(
        profileMapper,
        userMapper,
        mock(AssetBindingValidator.class),
        "/api/assets/images"
    );
    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final CreatorFollowApplicationService service = new CreatorFollowApplicationService(
        followMapper,
        profileMapper,
        userMapper,
        profileService,
        outboxWriter
    );

    @Test
    void followIsIdempotentAndDoesNotDoubleCount() {
        when(userMapper.selectById(10L)).thenReturn(activeUser());
        when(profileMapper.selectById(20L)).thenReturn(profile(3L));
        when(followMapper.insertFollow(20L, 10L)).thenReturn(0);
        when(followMapper.hasFollow(20L, 10L)).thenReturn(true);

        var result = service.follow("10", "20");

        assertThat(result.followedByMe()).isTrue();
        assertThat(result.followerCount()).isEqualTo(3);
    }

    @Test
    void followIncrementsOnlyOnNewRelation() {
        when(userMapper.selectById(10L)).thenReturn(activeUser());
        when(profileMapper.selectById(20L)).thenReturn(profile(2L), profile(3L));
        when(followMapper.insertFollow(20L, 10L)).thenReturn(1);
        when(followMapper.incrementFollowerCount(20L)).thenReturn(1);
        when(followMapper.hasFollow(20L, 10L)).thenReturn(true);

        assertThat(service.follow("10", "20").followerCount()).isEqualTo(3);
        verify(followMapper).incrementFollowerCount(20L);
        verify(outboxWriter).enqueue(20L, 10L, "FOLLOW", "20", "有人关注了你");
    }

    @Test
    void rejectsSelfFollowAndUnpublishedCreator() {
        assertThatThrownBy(() -> service.follow("10", "10")).isInstanceOf(IllegalArgumentException.class);
        when(userMapper.selectById(10L)).thenReturn(activeUser());
        CreatorProfileEntity unpublished = profile(0L);
        unpublished.setStatus("DRAFT");
        when(profileMapper.selectById(20L)).thenReturn(unpublished);
        assertThatThrownBy(() -> service.follow("10", "20")).isInstanceOf(CreatorProfileConflictException.class);
    }

    @Test
    void unfollowIsIdempotent() {
        when(userMapper.selectById(10L)).thenReturn(activeUser());
        when(profileMapper.selectById(20L)).thenReturn(profile(0L));
        when(followMapper.deleteFollow(20L, 10L)).thenReturn(0);

        assertThat(service.unfollow("10", "20").followedByMe()).isFalse();
    }

    @Test
    void followingPageCapsSizeAndIncludesTotal() {
        when(followMapper.selectFollowedCreators(10L, 50L, 50)).thenReturn(List.of(profile(2L)));
        when(followMapper.countFollowedCreators(10L)).thenReturn(101L);

        var page = service.following("10", 2, 1000);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(101);
        assertThat(page.size()).isEqualTo(50);
    }

    @Test
    void followerCursorIsBoundedAndOrderedByMapper() {
        when(followMapper.selectFollowerIdsAfter(20L, 100L, 100)).thenReturn(List.of(101L, 103L));

        assertThat(service.followerIds("20", "100", 1000)).containsExactly("101", "103");
        verify(followMapper).selectFollowerIdsAfter(20L, 100L, 100);
    }

    private UserAccount activeUser() {
        UserAccount user = new UserAccount();
        user.setId(10L);
        user.setStatus("ACTIVE");
        return user;
    }

    private CreatorProfileEntity profile(long followers) {
        CreatorProfileEntity profile = new CreatorProfileEntity();
        profile.setUserId(20L);
        profile.setSlug("creator-two");
        profile.setDisplayName("创作者二号");
        profile.setHeadline("");
        profile.setBio("");
        profile.setStatus("ACTIVE");
        profile.setVersion(1L);
        profile.setFollowerCount(followers);
        return profile;
    }
}
