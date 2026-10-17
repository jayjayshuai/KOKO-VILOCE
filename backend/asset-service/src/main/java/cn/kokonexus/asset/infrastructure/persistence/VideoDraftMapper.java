package cn.kokonexus.asset.infrastructure.persistence;

import cn.kokonexus.asset.domain.VideoDraft;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 视频额度、原请求和处理租约使用同库短事务及显式SQL。 */
public interface VideoDraftMapper extends BaseMapper<VideoDraft> {
    void ensureCapacity(@Param("owner") long owner);
    int lockGlobal();
    int reserve(@Param("owner") long owner, @Param("limit") int limit);
    VideoDraft original(@Param("owner") long owner, @Param("request") String request);
    int insertDraft(@Param("row") VideoDraft row);
    int queue(@Param("id") String id);
    int expire();
    int processingCount();
    VideoDraft next();
    int claim(@Param("id") String id, @Param("run") String run);
    boolean canProcess(@Param("id") String id, @Param("run") String run);
    int ready(@Param("row") VideoDraft row);
    int fail(@Param("id") String id, @Param("run") String run, @Param("code") String code);
    List<VideoDraft> owned(@Param("owner") long owner);
}
