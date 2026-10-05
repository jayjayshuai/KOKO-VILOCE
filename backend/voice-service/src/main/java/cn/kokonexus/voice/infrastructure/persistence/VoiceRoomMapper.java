package cn.kokonexus.voice.infrastructure.persistence;

import cn.kokonexus.voice.domain.VoiceRoom;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** voice-service：持久化映射；值参数绑定，复杂查询在 XML。 */
@Mapper
public interface VoiceRoomMapper extends BaseMapper<VoiceRoom> {
    /** 仅本人ID降序游标；最多sizePlusOne条，无总数扫描，不接受客户端房主参数。 */
    java.util.List<VoiceRoom> ownedRooms(
        @Param("ownerId") long ownerId,
        @Param("beforeId") Long beforeId,
        @Param("sizePlusOne") int sizePlusOne
    );
    int markOpen(@Param("id") long id, @Param("providerRoomName") String providerRoomName);
    int markFailed(@Param("id") long id);
    int markClosed(@Param("id") long id, @Param("ownerId") long ownerId);
}
