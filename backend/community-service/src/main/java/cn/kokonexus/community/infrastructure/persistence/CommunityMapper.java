package cn.kokonexus.community.infrastructure.persistence;

import cn.kokonexus.community.domain.Community;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** community-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface CommunityMapper extends BaseMapper<Community> {
    /** 同编辑/归档共用的当前社区行锁，先于成员读取和修改。 */
    Community lock(@Param("id") long id);

    /** 在社区锁下修改真实人数和版本；拒绝超过配额或移除最后一人。 */
    int changeMembers(@Param("id") long id, @Param("delta") int delta);

    /** 从成员关系查本人 ACTIVE 社区，游标按社区 ID 倒序。 */
    java.util.List<Community> joined(
        @Param("userId") long userId,
        @Param("before") Long before,
        @Param("size") int size
    );

    int updateOwned(
        @Param("id") long id,
        @Param("ownerId") long ownerId,
        @Param("expectedVersion") long expectedVersion,
        @Param("name") String name,
        @Param("description") String description,
        @Param("badge") String badge,
        @Param("visibility") String visibility
    );

    int archiveOwned(
        @Param("id") long id,
        @Param("ownerId") long ownerId,
        @Param("expectedVersion") long expectedVersion
    );
}
