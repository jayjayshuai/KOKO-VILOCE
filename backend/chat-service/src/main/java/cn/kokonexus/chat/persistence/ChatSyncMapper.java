package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.ChatSyncRevision;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 持久失效版本；值参数全部绑定，不读取其他业务库。 */
public interface ChatSyncMapper {
    /** 插入返回 1，已存在版本递增返回 1 或 2（依 JDBC affected-row 设置）。 */
    int advance(@Param("userId") long userId);
    /** 调用方限定非空且不超过 100 个用户；缺行表示版本 0。 */
    List<ChatSyncRevision> revisions(@Param("userIds") List<Long> userIds);
}
