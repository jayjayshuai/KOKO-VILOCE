package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatBookmark;
import cn.kokonexus.chat.domain.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 本人收藏 CRUD 与有界字面检索，复杂查询在 XML 内绑定参数。 */
@Mapper
public interface ArchiveMapper extends BaseMapper<ChatBookmark> {
    /** 只扫描已确认权限的会话序号窗口，最多取一条额外记录决定游标。 */
    List<ChatMessage> search(
        @Param("conversationId") String conversationId,
        @Param("floor") long floor,
        @Param("before") long before,
        @Param("pattern") String pattern,
        @Param("size") int size
    );

    /** 查询本人收藏的真实消息，历史边界必须来自当前成员事实。 */
    List<ChatMessage> bookmarked(
        @Param("userId") long userId,
        @Param("conversationId") String conversationId,
        @Param("joinedSeq") long joinedSeq,
        @Param("before") long before,
        @Param("size") int size
    );
}
