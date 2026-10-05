package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.Member;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 成员 CRUD 和单调游标写入。 */
public interface MemberMapper extends BaseMapper<Member> {
    /** 已持有会话锁后的当前成员读取；不能复用外层 RR 快照授予权限。 */
    Member currentMember(@Param("conversationId") String conversationId, @Param("userId") long userId);
    /** 会话锁先于成员锁，稳定用户顺序；用于收件人/群上限和成员投影。 */
    List<Member> currentMembers(@Param("conversationId") String conversationId);
    /** 当前事务内推进游标，不允许倒退。 */
    int markRead(@Param("conversationId") String conversationId, @Param("userId") long userId, @Param("seq") long seq);
}
