package cn.kokonexus.community.infrastructure.persistence;

import cn.kokonexus.community.domain.CommunityMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** community-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface CommunityMemberMapper extends BaseMapper<CommunityMember> {
    /** 锁定社区后使用当前读，避免外层 RR 旧快照保留已移除的授权。 */
    CommunityMember current(@Param("id") long id, @Param("userId") long userId);

    /** 当前成员名册，独占用户 ID 上界，调用方已锁定社区及验证权限。 */
    List<CommunityMember> page(@Param("id") long id, @Param("before") Long before, @Param("size") int size);
}
