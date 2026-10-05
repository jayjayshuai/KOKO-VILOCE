package cn.kokonexus.community.interfaces;

import cn.kokonexus.community.application.PostInteractionApplicationService;
import cn.kokonexus.community.application.PostInteractionApplicationService.CommentPage;
import cn.kokonexus.community.application.PostInteractionApplicationService.Engagement;
import cn.kokonexus.community.application.PostInteractionApplicationService.FavoritePage;
import cn.kokonexus.community.application.PostInteractionApplicationService.FavoriteState;
import cn.kokonexus.community.domain.CreatorPostComment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** community-service：HTTP 业务边界；身份校验与归属决策由网关及业务用例共同执行。 */
@RestController
@RequestMapping("/api")
@Tag(name = "内容互动")
public class PostInteractionController {

    /** PostInteractionApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final PostInteractionApplicationService applicationService;

    public PostInteractionController(PostInteractionApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PutMapping("/content/posts/{postId}/like")
    @Operation(summary = "点赞内容", description = "幂等操作，重复点赞不会重复计数。")
    public EngagementView like(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId
    ) {
        return EngagementView.from(applicationService.like(userId, postId));
    }

    @DeleteMapping("/content/posts/{postId}/like")
    @Operation(summary = "取消点赞", description = "幂等操作，重复取消不会产生负数计数。")
    public EngagementView unlike(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId
    ) {
        return EngagementView.from(applicationService.unlike(userId, postId));
    }

    @GetMapping("/content/posts/{postId}/engagement")
    @Operation(summary = "查询本人互动状态")
    public EngagementView engagement(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId
    ) {
        return EngagementView.from(applicationService.engagement(userId, postId));
    }

    @PutMapping("/content/posts/{postId}/favorite")
    @Operation(summary = "收藏内容", description = "幂等操作，重复收藏不会重复计数。")
    public FavoriteStateView favorite(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId
    ) {
        return FavoriteStateView.from(applicationService.favorite(userId, postId));
    }

    @DeleteMapping("/content/posts/{postId}/favorite")
    @Operation(summary = "取消收藏", description = "幂等操作，重复取消不会产生负数计数。")
    public FavoriteStateView unfavorite(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId
    ) {
        return FavoriteStateView.from(applicationService.unfavorite(userId, postId));
    }

    @GetMapping("/content/posts/{postId}/favorite")
    @Operation(summary = "查询本人收藏状态")
    public FavoriteStateView favoriteState(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId
    ) {
        return FavoriteStateView.from(applicationService.favoriteState(userId, postId));
    }

    @GetMapping("/content/favorites")
    @Operation(summary = "分页查询我的收藏")
    public FavoritePageView favorites(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int size
    ) {
        FavoritePage result = applicationService.favorites(userId, page, size);
        return new FavoritePageView(
            result.items().stream().map(CreatorPostController.PostSummaryView::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @PostMapping("/content/posts/{postId}/comments")
    @Operation(summary = "发表评论")
    public CommentView comment(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId,
        @Valid @RequestBody CreateCommentRequest request
    ) {
        return CommentView.from(applicationService.comment(userId, postId, request.body()));
    }

    @GetMapping("/discovery/posts/{slug}/comments")
    @Operation(summary = "查询公开评论")
    public List<CommentView> comments(@PathVariable String slug, @RequestParam(defaultValue = "50") int limit) {
        return applicationService.comments(slug, limit).stream().map(CommentView::from).toList();
    }

    @GetMapping("/discovery/posts/{slug}/comments/page")
    @Operation(summary = "分页查询公开评论")
    public CommentPageView commentsPage(
        @PathVariable String slug,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CommentPage result = applicationService.commentsPage(slug, page, size);
        return new CommentPageView(
            result.items().stream().map(CommentView::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @DeleteMapping("/content/posts/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除评论", description = "评论作者或内容所有者可执行软删除。")
    public void deleteComment(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId,
        @PathVariable long commentId,
        @RequestParam @PositiveOrZero long version
    ) {
        applicationService.deleteComment(userId, postId, commentId, version);
    }

    /** community-service：请求契约；字段校验以公开接口约束为准。 */
    public record CreateCommentRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "纯文本正文，不解释 HTML")
        @NotBlank
        @Size(max = 2000)
        String body
    ) {}

    /** community-service：EngagementView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record EngagementView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "点赞总数") long likeCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "评论总数") long commentCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前用户是否已点赞") boolean likedByMe
    ) {
        static EngagementView from(Engagement engagement) {
            return new EngagementView(engagement.likeCount(), engagement.commentCount(), engagement.likedByMe());
        }
    }

    /** community-service：FavoriteStateView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record FavoriteStateView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "收藏总数") long favoriteCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前用户是否已收藏") boolean favoritedByMe
    ) {
        static FavoriteStateView from(FavoriteState state) {
            return new FavoriteStateView(state.favoriteCount(), state.favoritedByMe());
        }
    }

    /** community-service：FavoritePageView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record FavoritePageView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表")
        List<CreatorPostController.PostSummaryView> items,
        /** community-service：CommentPageView 领域类型；字段单位、状态及可空性见各属性说明。 */
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}

    /** community-service：CommentPageView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record CommentPageView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<CommentView> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}

    /** community-service：CommentView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record CommentView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "所属文章 ID") String postId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "操作用户 ID") String userId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "纯文本正文，不解释 HTML") String body,
        @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值") long version,
        @io.swagger.v3.oas.annotations.media.Schema(description = "服务端创建时间，数据库时区 Asia/Shanghai")
        LocalDateTime createdAt
    ) {
        static CommentView from(CreatorPostComment comment) {
            return new CommentView(
                String.valueOf(comment.getId()),
                String.valueOf(comment.getPostId()),
                String.valueOf(comment.getUserId()),
                comment.getBody(),
                comment.getVersion(),
                comment.getCreatedAt()
            );
        }
    }
}
