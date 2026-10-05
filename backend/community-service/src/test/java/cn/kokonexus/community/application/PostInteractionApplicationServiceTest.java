package cn.kokonexus.community.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.community.domain.CreatorPost;
import cn.kokonexus.community.domain.CreatorPostComment;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostCommentMapper;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostMapper;
import cn.kokonexus.community.infrastructure.persistence.PostFavoriteMapper;
import cn.kokonexus.community.infrastructure.persistence.PostInteractionMapper;
import cn.kokonexus.outbox.OutboxWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostInteractionApplicationServiceTest {

    @Test
    void likeIncrementsOnlyWhenRelationIsNew() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "PUBLISHED", 4, 2));
        when(fixture.interactionMapper.insertLike(3001L, 2001L)).thenReturn(1);
        when(fixture.interactionMapper.incrementLikeCount(3001L)).thenReturn(1);

        var result = fixture.service.like(2001L, 3001L);

        assertThat(result.likeCount()).isEqualTo(5);
        assertThat(result.likedByMe()).isTrue();
    }

    @Test
    void repeatedLikeIsIdempotent() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "PUBLISHED", 4, 2));
        when(fixture.interactionMapper.insertLike(3001L, 2001L)).thenReturn(0);
        when(fixture.interactionMapper.hasLike(3001L, 2001L)).thenReturn(true);

        var result = fixture.service.like(2001L, 3001L);

        assertThat(result.likeCount()).isEqualTo(4);
        verify(fixture.interactionMapper, never()).incrementLikeCount(3001L);
    }

    @Test
    void commentPersistsAndIncrementsPublishedPostCount() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "PUBLISHED", 4, 2));
        when(fixture.commentMapper.insert(any(CreatorPostComment.class))).thenAnswer(invocation -> {
            CreatorPostComment comment = invocation.getArgument(0);
            comment.setId(4001L);
            return 1;
        });
        when(fixture.interactionMapper.incrementCommentCount(3001L)).thenReturn(1);

        CreatorPostComment result = fixture.service.comment(2001L, 3001L, " 真实评论 ");

        assertThat(result.getBody()).isEqualTo("真实评论");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getVersion()).isZero();
        verify(fixture.outboxWriter).enqueue(1001L, 2001L, "COMMENT", "3001", "你的内容收到一条新评论");
    }

    @Test
    void interactionsRejectUnpublishedPost() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "DRAFT", 0, 0));

        assertThatThrownBy(() -> fixture.service.like(2001L, 3001L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("内容不存在或尚未发布");
    }

    @Test
    void deleteCommentRejectsUnrelatedUser() {
        Fixture fixture = fixture();
        CreatorPostComment comment = comment(4001L, 3001L, 2001L, 0L, "ACTIVE");
        when(fixture.commentMapper.archiveAuthorized(4001L, 3001L, 9999L, 0L)).thenReturn(0);
        when(fixture.commentMapper.selectById(4001L)).thenReturn(comment);
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "PUBLISHED", 4, 2));

        assertThatThrownBy(() -> fixture.service.deleteComment(9999L, 3001L, 4001L, 0L))
            .isInstanceOf(ForbiddenOperationException.class)
            .hasMessage("只有评论作者或内容所有者可以删除评论");
    }

    @Test
    void postOwnerCanModerateComment() {
        Fixture fixture = fixture();
        when(fixture.commentMapper.archiveAuthorized(4001L, 3001L, 1001L, 0L)).thenReturn(1);

        fixture.service.deleteComment(1001L, 3001L, 4001L, 0L);

        verify(fixture.interactionMapper).decrementCommentCount(3001L);
    }

    @Test
    void repeatedFavoriteIsIdempotent() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "PUBLISHED", 4, 2));
        when(fixture.favoriteMapper.insertFavorite(3001L, 2001L)).thenReturn(0);
        when(fixture.favoriteMapper.hasFavorite(3001L, 2001L)).thenReturn(true);

        var result = fixture.service.favorite(2001L, 3001L);

        assertThat(result.favoriteCount()).isZero();
        assertThat(result.favoritedByMe()).isTrue();
        verify(fixture.favoriteMapper, never()).incrementFavoriteCount(3001L);
    }

    @Test
    void favoriteIncrementsRelationAndAggregateTogether() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectById(3001L)).thenReturn(post(3001L, 1001L, "PUBLISHED", 4, 2));
        when(fixture.favoriteMapper.insertFavorite(3001L, 2001L)).thenReturn(1);
        when(fixture.favoriteMapper.incrementFavoriteCount(3001L)).thenReturn(1);

        var result = fixture.service.favorite(2001L, 3001L);

        assertThat(result.favoriteCount()).isEqualTo(1);
    }

    @Test
    void commentPageCapsSizeAndReportsTotal() {
        Fixture fixture = fixture();
        when(fixture.postMapper.selectPublishedBySlug("sample-post")).thenReturn(
            post(3001L, 1001L, "PUBLISHED", 0, 51)
        );
        when(fixture.commentMapper.selectActivePageByPostSlug("sample-post", 50L, 50)).thenReturn(
            List.of(comment(4001L, 3001L, 2001L, 0L, "ACTIVE"))
        );
        when(fixture.commentMapper.countActiveByPostSlug("sample-post")).thenReturn(51L);

        var page = fixture.service.commentsPage("SAMPLE-POST", 2, 1000);

        assertThat(page.items()).hasSize(1);
        assertThat(page.size()).isEqualTo(50);
        assertThat(page.total()).isEqualTo(51);
    }

    @Test
    void unpublishedPostHasNoPublicCommentPage() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> fixture.service.commentsPage("hidden-post", 1, 20)).isInstanceOf(
            ResourceNotFoundException.class
        );
    }

    private Fixture fixture() {
        CreatorPostMapper postMapper = mock(CreatorPostMapper.class);
        CreatorPostCommentMapper commentMapper = mock(CreatorPostCommentMapper.class);
        PostInteractionMapper interactionMapper = mock(PostInteractionMapper.class);
        PostFavoriteMapper favoriteMapper = mock(PostFavoriteMapper.class);
        OutboxWriter outboxWriter = mock(OutboxWriter.class);
        return new Fixture(
            postMapper,
            commentMapper,
            interactionMapper,
            favoriteMapper,
            outboxWriter,
            new PostInteractionApplicationService(
                postMapper,
                commentMapper,
                interactionMapper,
                favoriteMapper,
                outboxWriter
            )
        );
    }

    private CreatorPost post(long id, long ownerId, String status, long likes, long comments) {
        CreatorPost post = new CreatorPost();
        post.setId(id);
        post.setOwnerId(ownerId);
        post.setStatus(status);
        post.setLikeCount(likes);
        post.setCommentCount(comments);
        post.setFavoriteCount(0L);
        return post;
    }

    private CreatorPostComment comment(long id, long postId, long userId, long version, String status) {
        CreatorPostComment comment = new CreatorPostComment();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setVersion(version);
        comment.setStatus(status);
        return comment;
    }

    private record Fixture(
        CreatorPostMapper postMapper,
        CreatorPostCommentMapper commentMapper,
        PostInteractionMapper interactionMapper,
        PostFavoriteMapper favoriteMapper,
        OutboxWriter outboxWriter,
        PostInteractionApplicationService service
    ) {}
}
