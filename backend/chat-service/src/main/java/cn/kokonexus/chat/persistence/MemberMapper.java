package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.Member;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** 成员 CRUD 和单调游标写入。 */
public interface MemberMapper extends BaseMapper<Member> {
    int markRead(@Param("conversationId") String conversationId, @Param("userId") long userId, @Param("seq") long seq);
}
