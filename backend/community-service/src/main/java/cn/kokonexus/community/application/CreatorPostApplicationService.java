package cn.kokonexus.community.application;

import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.api.asset.ManagedImageSelection;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.community.domain.CreatorPost;
import cn.kokonexus.community.infrastructure.AssetBindingValidator;
import cn.kokonexus.community.infrastructure.persistence.CreatorPostMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** community-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class CreatorPostApplicationService {

    /** CreatorPostMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CreatorPostMapper mapper;
    /** AssetBindingValidator 外部或领域适配器，失败不伪装为业务成功。 */
    private final AssetBindingValidator assetBindingValidator;
    /** 受管图片公开读取地址前缀。 */
    private final String assetPublicBase;

    public CreatorPostApplicationService(
        CreatorPostMapper mapper,
        AssetBindingValidator assetBindingValidator,
        @Value("${koko.asset.public-base:/api/assets/images}") String assetPublicBase
    ) {
        this.mapper = mapper;
        this.assetBindingValidator = assetBindingValidator;
        this.assetPublicBase = assetPublicBase;
    }

    @Transactional
    public CreatorPost create(
        long ownerId,
        String slug,
        String title,
        String excerpt,
        String body,
        String coverUrl,
        String coverAssetId
    ) {
        CreatorPost post = new CreatorPost();
        post.setOwnerId(ownerId);
        applyContent(post, null, ownerId, slug, title, excerpt, body, coverUrl, coverAssetId);
        post.setKind("ARTICLE");
        post.setStatus("DRAFT");
        post.setVersion(0L);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setFavoriteCount(0L);
        try {
            if (mapper.insert(post) != 1) {
                throw new IllegalStateException("内容草稿创建失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("内容地址已被占用", exception);
        }
        return presentation(post);
    }

    @Transactional(readOnly = true)
    public List<CreatorPost> ownedBy(long ownerId) {
        return mapper
            .selectList(
                Wrappers.<CreatorPost>lambdaQuery()
                    .eq(CreatorPost::getOwnerId, ownerId)
                    .ne(CreatorPost::getStatus, "ARCHIVED")
                    .orderByDesc(CreatorPost::getUpdatedAt, CreatorPost::getCreatedAt)
            )
            .stream()
            .map(this::presentation)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<CreatorPost> discover(int limit) {
        return mapper
            .selectPublished(Math.min(Math.max(limit, 1), 50))
            .stream()
            .map(this::presentation)
            .toList();
    }

    @Transactional(readOnly = true)
    public PostPage discoverPage(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        long total = mapper.countPublished();
        return new PostPage(
            mapper
                .selectPublishedPage((long) (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(this::presentation)
                .toList(),
            safePage,
            safeSize,
            total
        );
    }

    @Transactional(readOnly = true)
    public CreatorPost publishedBySlug(String slug) {
        CreatorPost post = mapper.selectPublishedBySlug(slug.toLowerCase(Locale.ROOT));
        if (post == null) {
            throw new ResourceNotFoundException("内容不存在或尚未发布");
        }
        return presentation(post);
    }

    @Transactional
    public CreatorPost update(
        long ownerId,
        long id,
        long expectedVersion,
        String slug,
        String title,
        String excerpt,
        String body,
        String coverUrl,
        String coverAssetId
    ) {
        CreatorPost current = mapper.selectById(id);
        if (current == null || !current.getOwnerId().equals(ownerId) || "ARCHIVED".equals(current.getStatus())) {
            explainRejectedMutation(ownerId, id, expectedVersion, false);
        }
        CreatorPost normalized = new CreatorPost();
        applyContent(normalized, current, ownerId, slug, title, excerpt, body, coverUrl, coverAssetId);
        try {
            if (
                mapper.updateOwned(
                    id,
                    ownerId,
                    expectedVersion,
                    normalized.getSlug(),
                    normalized.getTitle(),
                    normalized.getExcerpt(),
                    normalized.getBody(),
                    normalized.getCoverUrl(),
                    normalized.getCoverAssetId()
                ) != 1
            ) {
                explainRejectedMutation(ownerId, id, expectedVersion, false);
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("内容地址已被占用", exception);
        }
        return presentation(mapper.selectById(id));
    }

    @Transactional
    public CreatorPost publish(long ownerId, long id, long expectedVersion) {
        if (mapper.publishOwned(id, ownerId, expectedVersion) != 1) {
            explainRejectedMutation(ownerId, id, expectedVersion, true);
        }
        return presentation(mapper.selectById(id));
    }

    @Transactional
    public void archive(long ownerId, long id, long expectedVersion) {
        if (mapper.archiveOwned(id, ownerId, expectedVersion) != 1) {
            explainRejectedMutation(ownerId, id, expectedVersion, false);
        }
    }

    @Transactional(readOnly = true)
    public boolean isPublishedCover(String assetId) {
        return mapper.isPublishedCover(AssetUrl.canonicalId(assetId));
    }

    /** 只读核验所有状态封面；归档内容的恢复引用不能视为无人使用。 */
    @Transactional(readOnly = true)
    public boolean hasCoverReference(String assetId) {
        String canonical = AssetUrl.canonicalId(assetId);
        if (canonical == null) throw new IllegalArgumentException("媒体资产标识不能为空");
        return mapper.hasCoverReference(canonical);
    }

    private void applyContent(
        CreatorPost target,
        CreatorPost current,
        long ownerId,
        String slug,
        String title,
        String excerpt,
        String body,
        String coverUrl,
        String coverAssetId
    ) {
        target.setSlug(slug.trim().toLowerCase(Locale.ROOT));
        target.setTitle(title.trim());
        target.setExcerpt(excerpt == null ? "" : excerpt.trim());
        target.setBody(body.trim());
        ManagedImageSelection cover = ManagedImageSelection.choose(
            coverAssetId,
            coverUrl,
            current == null ? null : current.getCoverAssetId(),
            current == null ? null : current.getCoverUrl(),
            assetPublicBase,
            id -> assetBindingValidator.assertOwnedReady(ownerId, id, "POST_COVER")
        );
        target.setCoverUrl(cover.legacyUrl());
        target.setCoverAssetId(cover.assetId());
    }

    private CreatorPost presentation(CreatorPost post) {
        if (post != null && post.getCoverAssetId() != null) {
            post.setCoverUrl(AssetUrl.publicUrl(assetPublicBase, post.getCoverAssetId()));
        }
        return post;
    }

    private void explainRejectedMutation(long ownerId, long id, long expectedVersion, boolean publishing) {
        CreatorPost current = mapper.selectById(id);
        if (current == null) {
            throw new ResourceNotFoundException("内容不存在");
        }
        if (!current.getOwnerId().equals(ownerId)) {
            throw new ForbiddenOperationException("只有内容所有者可以执行此操作");
        }
        if ("ARCHIVED".equals(current.getStatus())) {
            throw new IllegalStateException("内容已归档");
        }
        if (!current.getVersion().equals(expectedVersion)) {
            throw new IllegalStateException("内容已被其他操作更新，请刷新后重试");
        }
        if (publishing && !"DRAFT".equals(current.getStatus())) {
            throw new IllegalStateException("只有草稿可以发布");
        }
        throw new IllegalStateException("内容状态已变化，请刷新后重试");
    }

    /** community-service：PostPage 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record PostPage(
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<CreatorPost> items,
        @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
        @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
        @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
    ) {}
}
