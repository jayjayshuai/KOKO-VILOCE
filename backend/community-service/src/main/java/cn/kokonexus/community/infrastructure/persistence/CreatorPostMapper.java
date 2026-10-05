package cn.kokonexus.community.infrastructure.persistence;

import cn.kokonexus.community.domain.CreatorPost;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** community-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface CreatorPostMapper extends BaseMapper<CreatorPost> {
    List<CreatorPost> selectPublished(@Param("limit") int limit);
    List<CreatorPost> selectPublishedPage(@Param("offset") long offset, @Param("size") int size);
    long countPublished();
    CreatorPost selectPublishedBySlug(@Param("slug") String slug);
    int updateOwned(
        @Param("id") long id,
        @Param("ownerId") long ownerId,
        @Param("expectedVersion") long expectedVersion,
        @Param("slug") String slug,
        @Param("title") String title,
        @Param("excerpt") String excerpt,
        @Param("body") String body,
        @Param("coverUrl") String coverUrl,
        @Param("coverAssetId") String coverAssetId
    );
    int publishOwned(
        @Param("id") long id,
        @Param("ownerId") long ownerId,
        @Param("expectedVersion") long expectedVersion
    );
    int archiveOwned(
        @Param("id") long id,
        @Param("ownerId") long ownerId,
        @Param("expectedVersion") long expectedVersion
    );

    boolean isPublishedCover(@Param("assetId") String assetId);

    /** 保留草稿及归档封面引用，防止恢复或继续编辑时丢失图片。 */
    boolean hasCoverReference(@Param("assetId") String assetId);
}
