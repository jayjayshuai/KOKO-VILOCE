package cn.kokonexus.community.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.community.domain.CreatorPost;
import cn.kokonexus.community.infrastructure.AssetBindingValidator;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorPostsServiceTest {

    @Test
    void fetchesOneExtraRowAndContinuesFromLastVisibleNotLookahead() {
        var mapper = mock(CreatorPostMapper.class);
        var time = LocalDateTime.of(2026, 10, 13, 22, 0, 0, 123456000);
        var first = row(31, time);
        var last = row(30, time);
        var extra = row(29, time);
        when(mapper.selectPublishedByAuthor(12, null, null, 3)).thenReturn(List.of(first, last, extra));
        var service = service(mapper);
        var result = service.publishedByAuthor(12, null, 2);
        assertThat(result.items()).containsExactly(first, last);
        assertThat(AuthorPostCursor.decode(result.nextCursor(), 12)).isEqualTo(new AuthorPostCursor(12, time, 30));
        when(mapper.selectPublishedByAuthor(12, time, 30L, 3)).thenReturn(List.of(extra));
        var next = service.publishedByAuthor(12, result.nextCursor(), 2);
        assertThat(next.items()).containsExactly(extra);
        assertThat(next.nextCursor()).isNull();
        verify(mapper).selectPublishedByAuthor(12, time, 30L, 3);
    }

    @Test
    void nonexistentAuthorEmptyReadDoesNotProbePrivateUserState() {
        var mapper = mock(CreatorPostMapper.class);
        when(mapper.selectPublishedByAuthor(12, null, null, 13)).thenReturn(List.of());
        var result = service(mapper).publishedByAuthor(12, null, 12);
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.timeZone()).isEqualTo("UTC");
        verify(mapper).selectPublishedByAuthor(12, null, null, 13);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void explicitStorageZoneIsValidatedAndDoesNotRewriteRawPublicationTime() {
        var mapper = mock(CreatorPostMapper.class);
        var binding = mock(AssetBindingValidator.class);
        var time = LocalDateTime.of(2026, 10, 13, 14, 0);
        var post = row(31, time);
        when(mapper.selectPublishedByAuthor(12, null, null, 13)).thenReturn(List.of(post));
        var value = new CreatorPostApplicationService(
            mapper,
            binding,
            "/api/assets/images",
            "Asia/Shanghai"
        ).publishedByAuthor(12, null, 12);
        assertThat(value.timeZone()).isEqualTo("Asia/Shanghai");
        assertThat(value.items().getFirst().getPublishedAt()).isEqualTo(time);
        assertThatThrownBy(() ->
            new CreatorPostApplicationService(mapper, binding, "/api/assets/images", "invalid")
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidOwnerSizeOrForeignCursorNeverCallsDatabase() {
        var mapper = mock(CreatorPostMapper.class);
        var service = service(mapper);
        assertThatThrownBy(() -> service.publishedByAuthor(0, null, 12)).isInstanceOf(IllegalArgumentException.class);
        for (var size : new int[] { 0, 31, Integer.MAX_VALUE })
            assertThatThrownBy(() -> service.publishedByAuthor(12, null, size)).isInstanceOf(
                IllegalArgumentException.class
            );
        var foreign = new AuthorPostCursor(13, LocalDateTime.of(2026, 1, 1, 0, 0), 1).encode();
        assertThatThrownBy(() -> service.publishedByAuthor(12, foreign, 12)).isInstanceOf(
            IllegalArgumentException.class
        );
        verifyNoInteractions(mapper);
    }

    private CreatorPost row(long id, LocalDateTime time) {
        var post = new CreatorPost();
        post.setId(id);
        post.setOwnerId(12L);
        post.setStatus("PUBLISHED");
        post.setPublishedAt(time);
        return post;
    }

    private CreatorPostApplicationService service(CreatorPostMapper mapper) {
        return new CreatorPostApplicationService(mapper, mock(AssetBindingValidator.class), "/api/assets/images");
    }
}
