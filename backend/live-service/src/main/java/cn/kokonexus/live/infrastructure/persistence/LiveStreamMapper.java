package cn.kokonexus.live.infrastructure.persistence;

import cn.kokonexus.live.domain.LiveStream;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/** live-service：持久化映射；值参数绑定，复杂查询在 XML。 */
public interface LiveStreamMapper extends BaseMapper<LiveStream> {
    int transitionStatus(
        @Param("streamId") long streamId,
        @Param("creatorId") long creatorId,
        @Param("expectedStatus") String expectedStatus,
        @Param("targetStatus") String targetStatus
    );
}
