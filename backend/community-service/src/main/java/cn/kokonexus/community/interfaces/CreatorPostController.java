package cn.kokonexus.community.interfaces;

import cn.kokonexus.community.application.CreatorPostApplicationService;
import cn.kokonexus.community.application.CreatorPostApplicationService.PostPage;
import cn.kokonexus.community.domain.CreatorPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
@Tag(name = "UP 主内容")
public class CreatorPostController {

    /** CreatorPostApplicationService 业务用例依赖，事务由 Spring 代理管理。 */
    private final CreatorPostApplicationService applicationService;

    public CreatorPostController(CreatorPostApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/content/posts")
    @Operation(summary = "创建内容草稿")
    public PostDetailView create(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @Valid @RequestBody SavePostRequest request
    ) {
        return PostDetailView.from(
            applicationService.create(
                userId,
                request.slug(),
                request.title(),
                request.excerpt(),
                request.body(),
                request.coverUrl(),
                request.coverAssetId()
            )
        );
    }

    @GetMapping("/content/posts/mine")
    @Operation(summary = "查询本人的内容")
    public List<PostDetailView> mine(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId
    ) {
        return applicationService.ownedBy(userId).stream().map(PostDetailView::from).toList();
    }

    @PutMapping("/content/posts/{postId}")
    @Operation(summary = "修改内容", description = "仅所有者可操作，版本号用于防止并发覆盖。")
    public PostDetailView update(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId,
        @Valid @RequestBody UpdatePostRequest request
    ) {
        return PostDetailView.from(
            applicationService.update(
                userId,
                postId,
                request.version(),
                request.slug(),
                request.title(),
                request.excerpt(),
                request.body(),
                request.coverUrl(),
                request.coverAssetId()
            )
        );
    }

    @PostMapping("/content/posts/{postId}/publish")
    @Operation(summary = "发布内容", description = "内容从草稿进入公开发布状态。")
    public PostDetailView publish(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId,
        @RequestParam @PositiveOrZero long version
    ) {
        return PostDetailView.from(applicationService.publish(userId, postId, version));
    }

    @DeleteMapping("/content/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "归档内容", description = "归档后不再公开展示，数据不会被物理删除。")
    public void archive(
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("X-Koko-User-Id") long userId,
        @PathVariable long postId,
        @RequestParam @PositiveOrZero long version
    ) {
        applicationService.archive(userId, postId, version);
    }

    @GetMapping("/discovery/posts")
    @Operation(summary = "发现已发布内容")
    public List<PostSummaryView> discover(@RequestParam(defaultValue = "12") int limit) {
        return applicationService.discover(limit).stream().map(PostSummaryView::from).toList();
    }

    @GetMapping("/discovery/posts/page")
    @Operation(summary = "分页发现已发布内容")
    public PostPageView discoverPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int size
    ) {
        PostPage result = applicationService.discoverPage(page, size);
        return new PostPageView(
            result.items().stream().map(PostSummaryView::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @GetMapping("/discovery/posts/{slug}")
    @Operation(summary = "按地址读取已发布内容")
    public PostDetailView detail(@PathVariable String slug) {
        return PostDetailView.from(applicationService.publishedBySlug(slug));
    }

    /** community-service：请求契约；字段校验以公开接口约束为准。 */
    public record SavePostRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识")
        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,78}[a-z0-9]")
        String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") @NotBlank @Size(max = 160) String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "内容摘要") @Size(max = 300) String excerpt,
        @io.swagger.v3.oas.annotations.media.Schema(description = "纯文本正文，不解释 HTML")
        @NotBlank
        @Size(max = 100000)
        String body,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开封面地址") @Size(max = 1000) String coverUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的封面资产 UUID") String coverAssetId
    ) {}

    /** community-service：请求契约；字段校验以公开接口约束为准。 */
    public record UpdatePostRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识")
        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,78}[a-z0-9]")
        String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") @NotBlank @Size(max = 160) String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "内容摘要") @Size(max = 300) String excerpt,
        @io.swagger.v3.oas.annotations.media.Schema(description = "纯文本正文，不解释 HTML")
        @NotBlank
        @Size(max = 100000)
        String body,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开封面地址") @Size(max = 1000) String coverUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的封面资产 UUID") String coverAssetId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值")
        @PositiveOrZero
        long version
    ) {}

    /** community-service：PostSummaryView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record PostSummaryView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "资源所有者用户 ID") String ownerId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识") String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "内容摘要") String excerpt,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开封面地址") String coverUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的封面资产 UUID") String coverAssetId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "领域资源类型") String kind,
        @io.swagger.v3.oas.annotations.media.Schema(description = "点赞总数") long likeCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "评论总数") long commentCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "收藏总数") long favoriteCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "正式发布时间；未发布为空，Asia/Shanghai")
        LocalDateTime publishedAt
    ) {
        static PostSummaryView from(CreatorPost post) {
            return new PostSummaryView(
                String.valueOf(post.getId()),
                String.valueOf(post.getOwnerId()),
                post.getSlug(),
                post.getTitle(),
                post.getExcerpt(),
                post.getCoverUrl(),
                post.getCoverAssetId(),
                post.getKind(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getFavoriteCount(),
                post.getPublishedAt()
            );
        }
    }

    /** community-service：PostDetailView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record PostDetailView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(description = "资源所有者用户 ID") String ownerId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识") String slug,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务标题") String title,
        @io.swagger.v3.oas.annotations.media.Schema(description = "内容摘要") String excerpt,
        @io.swagger.v3.oas.annotations.media.Schema(description = "纯文本正文，不解释 HTML") String body,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开封面地址") String coverUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的封面资产 UUID") String coverAssetId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "领域资源类型") String kind,
        @io.swagger.v3.oas.annotations.media.Schema(description = "业务状态，允许值以所属领域状态机为准") String status,
        @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值") long version,
        @io.swagger.v3.oas.annotations.media.Schema(description = "点赞总数") long likeCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "评论总数") long commentCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "收藏总数") long favoriteCount,
        @io.swagger.v3.oas.annotations.media.Schema(description = "正式发布时间；未发布为空，Asia/Shanghai")
        LocalDateTime publishedAt,
        @io.swagger.v3.oas.annotations.media.Schema(description = "服务端最后修改时间，Asia/Shanghai")
        LocalDateTime updatedAt
    ) {
        static PostDetailView from(CreatorPost post) {
            return new PostDetailView(
                String.valueOf(post.getId()),
                String.valueOf(post.getOwnerId()),
                post.getSlug(),
                post.getTitle(),
                post.getExcerpt(),
                post.getBody(),
                post.getCoverUrl(),
                post.getCoverAssetId(),
                post.getKind(),
                post.getStatus(),
                post.getVersion(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getFavoriteCount(),
                post.getPublishedAt(),
                post.getUpdatedAt()
            );
        }
    }

    /** community-service：PostPageView 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record PostPageView(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<PostSummaryView> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}
}
