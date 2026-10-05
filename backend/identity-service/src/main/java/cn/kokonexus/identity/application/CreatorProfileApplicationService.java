package cn.kokonexus.identity.application;

import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.api.asset.ManagedImageSelection;
import cn.kokonexus.api.identity.CreatorPage;
import cn.kokonexus.api.identity.CreatorProfile;
import cn.kokonexus.api.identity.CreatorProfileConflictException;
import cn.kokonexus.api.identity.SaveCreatorProfileCommand;
import cn.kokonexus.identity.domain.CreatorProfileEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.AssetBindingValidator;
import cn.kokonexus.identity.infrastructure.persistence.CreatorProfileMapper;
import cn.kokonexus.identity.infrastructure.persistence.UserMapper;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** identity-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class CreatorProfileApplicationService {

    /** 服务端格式校验规则，禁止客户端覆盖。 */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])?$");
    /** CreatorProfileMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final CreatorProfileMapper creatorProfileMapper;
    /** UserMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final UserMapper userMapper;
    /** AssetBindingValidator 外部或领域适配器，失败不伪装为业务成功。 */
    private final AssetBindingValidator assetBindingValidator;
    /** 受管图片公开读取地址前缀。 */
    private final String assetPublicBase;

    public CreatorProfileApplicationService(
        CreatorProfileMapper creatorProfileMapper,
        UserMapper userMapper,
        AssetBindingValidator assetBindingValidator,
        @Value("${koko.asset.public-base:/api/assets/images}") String assetPublicBase
    ) {
        this.creatorProfileMapper = creatorProfileMapper;
        this.userMapper = userMapper;
        this.assetBindingValidator = assetBindingValidator;
        this.assetPublicBase = assetPublicBase;
    }

    @Transactional(readOnly = true)
    public CreatorProfile findMine(String userId) {
        CreatorProfileEntity profile = creatorProfileMapper.selectById(parseUserId(userId));
        return profile == null ? null : toProfile(profile);
    }

    @Transactional(readOnly = true)
    public CreatorProfile findPublished(String slug) {
        if (slug == null) return null;
        CreatorProfileEntity profile = creatorProfileMapper.selectPublishedBySlug(slug.trim().toLowerCase(Locale.ROOT));
        return profile == null ? null : toProfile(profile);
    }

    @Transactional(readOnly = true)
    public boolean isPublishedAsset(String assetId) {
        return creatorProfileMapper.isPublishedAsset(AssetUrl.canonicalId(assetId));
    }

    /** 所有状态均参与引用保护；不返回引用者身份或把私有图片设为公开。 */
    @Transactional(readOnly = true)
    public boolean hasAssetReference(String assetId) {
        String canonical = AssetUrl.canonicalId(assetId);
        if (canonical == null) throw new IllegalArgumentException("媒体资产标识不能为空");
        return creatorProfileMapper.hasAssetReference(canonical);
    }

    @Transactional(readOnly = true)
    public List<CreatorProfile> listPublished(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return creatorProfileMapper.selectPublished(safeLimit).stream().map(this::toProfile).toList();
    }

    @Transactional(readOnly = true)
    public CreatorPage pagePublished(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        return new CreatorPage(
            creatorProfileMapper
                .selectPublishedPage((long) (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(this::toProfile)
                .toList(),
            safePage,
            safeSize,
            creatorProfileMapper.countPublished()
        );
    }

    @Transactional
    public CreatorProfile save(String userId, SaveCreatorProfileCommand command) {
        long ownerId = parseUserId(userId);
        requireActiveUser(ownerId);
        CreatorProfileEntity current = creatorProfileMapper.selectById(ownerId);
        CreatorProfileEntity submitted = validated(ownerId, command, current);
        try {
            if (current == null) {
                if (command.version() != 0) throw new CreatorProfileConflictException(
                    "创作者资料版本已失效，请刷新后重试"
                );
                submitted.setStatus("DRAFT");
                submitted.setVersion(0L);
                submitted.setFollowerCount(0L);
                if (creatorProfileMapper.insert(submitted) != 1) throw new IllegalStateException("创作者资料创建失败");
                return toProfile(submitted);
            }
            if (creatorProfileMapper.updateOwnedProfile(submitted, command.version()) != 1) {
                throw new CreatorProfileConflictException("创作者资料已被修改，请刷新后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("创作者主页地址已被占用", exception);
        }
        CreatorProfileEntity saved = creatorProfileMapper.selectById(ownerId);
        if (saved == null) throw new IllegalStateException("创作者资料保存后无法读取");
        return toProfile(saved);
    }

    @Transactional
    public CreatorProfile publish(String userId, long version) {
        long ownerId = parseUserId(userId);
        requireActiveUser(ownerId);
        CreatorProfileEntity current = creatorProfileMapper.selectById(ownerId);
        if (current == null) throw new CreatorProfileConflictException("请先保存创作者资料");
        if ("SUSPENDED".equals(current.getStatus())) throw new CreatorProfileConflictException(
            "创作者主页当前不可发布"
        );
        if ("ACTIVE".equals(current.getStatus())) return toProfile(current);
        if (creatorProfileMapper.publishOwnedProfile(ownerId, version) != 1) {
            throw new CreatorProfileConflictException("创作者资料已被修改，请刷新后重试");
        }
        CreatorProfileEntity published = creatorProfileMapper.selectById(ownerId);
        if (published == null) throw new IllegalStateException("创作者主页发布后无法读取");
        return toProfile(published);
    }

    private CreatorProfileEntity validated(
        long userId,
        SaveCreatorProfileCommand command,
        CreatorProfileEntity current
    ) {
        if (command == null) throw new IllegalArgumentException("创作者资料不能为空");
        String slug = text(command.slug()).toLowerCase(Locale.ROOT);
        if (!SLUG_PATTERN.matcher(slug).matches()) throw new IllegalArgumentException(
            "主页地址只能使用 3 到 64 位小写字母、数字和连字符"
        );
        String displayName = text(command.displayName());
        if (displayName.isBlank() || displayName.length() > 80) throw new IllegalArgumentException(
            "创作者名称不能为空且不能超过 80 个字符"
        );
        String headline = text(command.headline());
        String bio = text(command.bio());
        if (headline.length() > 120) throw new IllegalArgumentException("创作者简介标题不能超过 120 个字符");
        if (bio.length() > 1000) throw new IllegalArgumentException("创作者介绍不能超过 1000 个字符");
        ManagedImageSelection avatar = ManagedImageSelection.choose(
            command.avatarAssetId(),
            command.avatarUrl(),
            current == null ? null : current.getAvatarAssetId(),
            current == null ? null : current.getAvatarUrl(),
            assetPublicBase,
            id -> assetBindingValidator.assertOwnedReady(userId, id, "AVATAR")
        );
        ManagedImageSelection banner = ManagedImageSelection.choose(
            command.bannerAssetId(),
            command.bannerUrl(),
            current == null ? null : current.getBannerAssetId(),
            current == null ? null : current.getBannerUrl(),
            assetPublicBase,
            id -> assetBindingValidator.assertOwnedReady(userId, id, "BANNER")
        );
        CreatorProfileEntity entity = new CreatorProfileEntity();
        entity.setUserId(userId);
        entity.setSlug(slug);
        entity.setDisplayName(displayName);
        entity.setHeadline(headline);
        entity.setBio(bio);
        entity.setAvatarUrl(avatar.legacyUrl());
        entity.setAvatarAssetId(avatar.assetId());
        entity.setBannerUrl(banner.legacyUrl());
        entity.setBannerAssetId(banner.assetId());
        return entity;
    }

    private void requireActiveUser(long userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new IllegalArgumentException("账号当前不可用");
    }

    private long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("用户标识无效", exception);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    CreatorProfile toProfile(CreatorProfileEntity entity) {
        return new CreatorProfile(
            String.valueOf(entity.getUserId()),
            entity.getSlug(),
            entity.getDisplayName(),
            entity.getHeadline(),
            entity.getBio(),
            entity.getAvatarAssetId() == null
                ? entity.getAvatarUrl()
                : AssetUrl.publicUrl(assetPublicBase, entity.getAvatarAssetId()),
            entity.getBannerAssetId() == null
                ? entity.getBannerUrl()
                : AssetUrl.publicUrl(assetPublicBase, entity.getBannerAssetId()),
            entity.getAvatarAssetId(),
            entity.getBannerAssetId(),
            entity.getStatus(),
            entity.getVersion(),
            entity.getFollowerCount() == null ? 0 : entity.getFollowerCount()
        );
    }
}
