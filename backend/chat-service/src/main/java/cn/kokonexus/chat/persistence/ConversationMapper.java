package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.Conversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 会话 CRUD 和并发关键查询；复杂 SQL 在 XML。 */
public interface ConversationMapper extends BaseMapper<Conversation> {
    /** 事务内锁定唯一会话，所有发消息和成员管理采用同一锁顺序。 */
    Conversation lock(@Param("id") String id);
    /** UUID 稳定游标；单页最多 100 条。 */
    List<Conversation> listMine(@Param("userId") long userId, @Param("after") String after, @Param("size") int size);
}
