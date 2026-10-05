package cn.kokonexus.community.application;

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
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** community-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class PostInteractionApplicationService {

    /** CreatorPostMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CreatorPostMapper postMapper;
    /** CreatorPostCommentMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CreatorPostCommentMapper commentMapper;
    /** PostInteractionMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final PostInteractionMapper interactionMapper;
    /** PostFavoriteMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final PostFavoriteMapper favoriteMapper;
    /** OutboxWriter 外部或领域适配器，失败不伪装为业务成功。 */
    private final OutboxWriter outboxWriter;

    public PostInteractionApplicationService(
        CreatorPostMapper postMapper,
        CreatorPostCommentMapper commentMapper,
        PostInteractionMapper interactionMapper,
        PostFavoriteMapper favoriteMapper,
        OutboxWriter outboxWriter
    ) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.interactionMapper = interactionMapper;
        this.favoriteMapper = favoriteMapper;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public Engagement like(long userId, long postId) {
        CreatorPost post = requirePublished(postId);
        if (interactionMapper.insertLike(postId, userId) == 1) {
            if (interactionMapper.incrementLikeCount(postId) != 1) {
                throw new IllegalStateException("内容状态已变化，请刷新后重试");
            }
            post.setLikeCount(post.getLikeCount() + 1);
        } else if (!interactionMapper.hasLike(postId, userId)) {
            throw new IllegalStateException("点赞状态已变化，请刷新后重试");
        }
        return new Engagement(post.getLikeCount(), post.getCommentCount(), true);
    }

    @Transactional
    public Engagement unlike(long userId, long postId) {
        CreatorPost post = requirePublished(postId);
        if (interactionMapper.deleteLike(postId, userId) == 1) {
            if (interactionMapper.decrementLikeCount(postId) != 1) {
                throw new IllegalStateException("点赞计数更新失败");
            }
            post.setLikeCount(Math.max(post.getLikeCount() - 1, 0));
        }
        return new Engagement(post.getLikeCount(), post.getCommentCount(), false);
    }

    @Transactional(readOnly = true)
    public Engagement engagement(long userId, long postId) {
        CreatorPost post = requirePublished(postId);
        return new Engagement(post.getLikeCount(), post.getCommentCount(), interactionMapper.hasLike(postId, userId));
    }

    @Transactional
    public FavoriteState favorite(long userId, long postId) {
        CreatorPost post = requirePublished(postId);
        if (favoriteMapper.insertFavorite(postId, userId) == 1) {
            if (favoriteMapper.incrementFavoriteCount(postId) != 1) {
                throw new IllegalStateException("内容状态已变化，请刷新后重试");
            }
            post.setFavoriteCount(post.getFavoriteCount() + 1);
        } else if (!favoriteMapper.hasFavorite(postId, userId)) {
            throw new IllegalStateException("收藏状态已变化，请刷新后重试");
        }
        return new FavoriteState(post.getFavoriteCount(), true);
    }

    @Transactional
    public FavoriteState unfavorite(long userId, long postId) {
        CreatorPost post = requirePublished(postId);
        if (favoriteMapper.deleteFavorite(postId, userId) == 1) {
            if (favoriteMapper.decrementFavoriteCount(postId) != 1) {
                throw new IllegalStateException("收藏计数更新失败");
            }
            post.setFavoriteCount(Math.max(post.getFavoriteCount() - 1, 0));
        }
        return new FavoriteState(post.getFavoriteCount(), false);
    }

    @Transactional(readOnly = true)
    public FavoriteState favoriteState(long userId, long postId) {
        CreatorPost post = requirePublished(postId);
        return new FavoriteState(post.getFavoriteCount(), favoriteMapper.hasFavorite(postId, userId));
    }

    @Transactional(readOnly = true)
    public FavoritePage favorites(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        return new FavoritePage(
            favoriteMapper.selectFavoritePosts(userId, (long) (safePage - 1) * safeSize, safeSize),
            safePage,
            safeSize,
            favoriteMapper.countFavoritePosts(userId)
        );
    }

    @Transactional
    public CreatorPostComment comment(long userId, long postId, String body) {
        CreatorPost post = requirePublished(postId);
        CreatorPostComment comment = new CreatorPostComment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setBody(body.trim());
        comment.setStatus("ACTIVE");
        comment.setVersion(0L);
        if (commentMapper.insert(comment) != 1 || interactionMapper.incrementCommentCount(postId) != 1) {
            throw new IllegalStateException("评论发布失败");
        }
        if (!post.getOwnerId().equals(userId)) {
            outboxWriter.enqueue(
                post.getOwnerId(),
                userId,
                "COMMENT",
                String.valueOf(postId),
                "你的内容收到一条新评论"
            );
        }
        return comment;
    }

    @Transactional(readOnly = true)
    public List<CreatorPostComment> comments(String slug, int limit) {
        String normalizedSlug = slug.toLowerCase(Locale.ROOT);
        if (postMapper.selectPublishedBySlug(normalizedSlug) == null) {
            throw new ResourceNotFoundException("内容不存在或尚未发布");
        }
        return commentMapper.selectActiveByPostSlug(normalizedSlug, Math.min(Math.max(limit, 1), 100));
    }

    @Transactional(readOnly = true)
    public CommentPage commentsPage(String slug, int page, int size) {
        String normalizedSlug = slug.toLowerCase(Locale.ROOT);
        if (postMapper.selectPublishedBySlug(normalizedSlug) == null) {
            throw new ResourceNotFoundException("内容不存在或尚未发布");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        return new CommentPage(
            commentMapper.selectActivePageByPostSlug(normalizedSlug, (long) (safePage - 1) * safeSize, safeSize),
            safePage,
            safeSize,
            commentMapper.countActiveByPostSlug(normalizedSlug)
        );
    }

    @Transactional
    public void deleteComment(long actorId, long postId, long commentId, long expectedVersion) {
        if (commentMapper.archiveAuthorized(commentId, postId, actorId, expectedVersion) != 1) {
            explainRejectedCommentDelete(actorId, postId, commentId, expectedVersion);
        }
        interactionMapper.decrementCommentCount(postId);
    }

    private CreatorPost requirePublished(long postId) {
        CreatorPost post = postMapper.selectById(postId);
        if (post == null || !"PUBLISHED".equals(post.getStatus())) {
            throw new ResourceNotFoundException("内容不存在或尚未发布");
        }
        return post;
    }

    private void explainRejectedCommentDelete(long actorId, long postId, long commentId, long expectedVersion) {
        CreatorPostComment comment = commentMapper.selectById(commentId);
        if (comment == null || !comment.getPostId().equals(postId)) {
            throw new ResourceNotFoundException("评论不存在");
        }
        CreatorPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new ResourceNotFoundException("内容不存在");
        }
        if (!comment.getUserId().equals(actorId) && !post.getOwnerId().equals(actorId)) {
            throw new ForbiddenOperationException("只有评论作者或内容所有者可以删除评论");
        }
        if (!"ACTIVE".equals(comment.getStatus())) {
            throw new IllegalStateException("评论已删除");
        }
        if (!comment.getVersion().equals(expectedVersion)) {
            throw new IllegalStateException("评论已被其他操作更新，请刷新后重试");
        }
        throw new IllegalStateException("评论状态已变化，请刷新后重试");
    }

    /** community-service：Engagement 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record Engagement(
        @io.swagger.v3.oas.annotations.media.Schema(description = "点赞总数") long likeCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "评论总数") long commentCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前用户是否已点赞") boolean likedByMe
    ) {}

    /** community-service：FavoriteState 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record FavoriteState(
        @io.swagger.v3.oas.annotations.media.Schema(description = "收藏总数") long favoriteCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前用户是否已收藏") boolean favoritedByMe
    ) {}

    /** community-service：FavoritePage 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record FavoritePage(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<CreatorPost> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}

    /** community-service：CommentPage 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record CommentPage(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表")
        List<CreatorPostComment> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}
}
