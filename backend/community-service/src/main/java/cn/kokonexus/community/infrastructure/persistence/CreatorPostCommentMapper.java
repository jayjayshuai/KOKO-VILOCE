package cn.kokonexus.community.infrastructure.persistence;

import cn.kokonexus.community.domain.CreatorPostComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** community-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface CreatorPostCommentMapper extends BaseMapper<CreatorPostComment> {
    List<CreatorPostComment> selectActiveByPostSlug(@Param("slug") String slug, @Param("limit") int limit);
    List<CreatorPostComment> selectActivePageByPostSlug(
        @Param("slug") String slug,
        @Param("offset") long offset,
        @Param("size") int size
    );
    long countActiveByPostSlug(@Param("slug") String slug);
    int archiveAuthorized(
        @Param("id") long id,
        @Param("postId") long postId,
        @Param("actorId") long actorId,
        @Param("expectedVersion") long expectedVersion
    );
}
