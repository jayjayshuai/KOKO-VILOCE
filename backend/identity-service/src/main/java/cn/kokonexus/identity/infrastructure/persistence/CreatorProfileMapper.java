package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.CreatorProfileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** identity-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface CreatorProfileMapper extends BaseMapper<CreatorProfileEntity> {
    int updateOwnedProfile(
        @Param("profile") CreatorProfileEntity profile,
        @Param("expectedVersion") long expectedVersion
    );

    int publishOwnedProfile(@Param("userId") long userId, @Param("expectedVersion") long expectedVersion);

    CreatorProfileEntity selectPublishedBySlug(@Param("slug") String slug);

    List<CreatorProfileEntity> selectPublished(@Param("limit") int limit);

    List<CreatorProfileEntity> selectPublishedPage(@Param("offset") long offset, @Param("size") int size);

    long countPublished();

    boolean isPublishedAsset(@Param("assetId") String assetId);

    /** 不按发布状态过滤，避免将仍被私有主页引用的图片误判为孤儿。 */
    boolean hasAssetReference(@Param("assetId") String assetId);
}
