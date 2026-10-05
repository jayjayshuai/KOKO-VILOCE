package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.CreatorProfileEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** identity-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface CreatorFollowMapper {
    int insertFollow(@Param("creatorId") long creatorId, @Param("followerId") long followerId);

    int deleteFollow(@Param("creatorId") long creatorId, @Param("followerId") long followerId);

    boolean hasFollow(@Param("creatorId") long creatorId, @Param("followerId") long followerId);

    int incrementFollowerCount(@Param("creatorId") long creatorId);

    int decrementFollowerCount(@Param("creatorId") long creatorId);

    List<CreatorProfileEntity> selectFollowedCreators(
        @Param("followerId") long followerId,
        @Param("offset") long offset,
        @Param("size") int size
    );

    long countFollowedCreators(@Param("followerId") long followerId);

    List<Long> selectFollowerIdsAfter(
        @Param("creatorId") long creatorId,
        @Param("afterId") long afterId,
        @Param("size") int size
    );
}
