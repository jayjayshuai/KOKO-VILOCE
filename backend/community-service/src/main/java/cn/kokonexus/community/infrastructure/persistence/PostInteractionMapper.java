package cn.kokonexus.community.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;

/** community-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface PostInteractionMapper {
    int insertLike(@Param("postId") long postId, @Param("userId") long userId);
    int deleteLike(@Param("postId") long postId, @Param("userId") long userId);
    boolean hasLike(@Param("postId") long postId, @Param("userId") long userId);
    int incrementLikeCount(@Param("postId") long postId);
    int decrementLikeCount(@Param("postId") long postId);
    int incrementCommentCount(@Param("postId") long postId);
    int decrementCommentCount(@Param("postId") long postId);
}
