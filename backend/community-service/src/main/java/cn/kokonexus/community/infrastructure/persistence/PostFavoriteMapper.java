package cn.kokonexus.community.infrastructure.persistence;

import cn.kokonexus.community.domain.CreatorPost;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** community-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface PostFavoriteMapper {
    int insertFavorite(@Param("postId") long postId, @Param("userId") long userId);
    int deleteFavorite(@Param("postId") long postId, @Param("userId") long userId);
    boolean hasFavorite(@Param("postId") long postId, @Param("userId") long userId);
    int incrementFavoriteCount(@Param("postId") long postId);
    int decrementFavoriteCount(@Param("postId") long postId);
    List<CreatorPost> selectFavoritePosts(
        @Param("userId") long userId,
        @Param("offset") long offset,
        @Param("size") int size
    );
    long countFavoritePosts(@Param("userId") long userId);
}
