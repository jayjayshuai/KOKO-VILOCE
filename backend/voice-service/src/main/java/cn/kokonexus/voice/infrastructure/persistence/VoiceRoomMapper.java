package cn.kokonexus.voice.infrastructure.persistence;

import cn.kokonexus.voice.domain.VoiceRoom;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** voice-service：持久化映射；值参数绑定，复杂查询在 XML。 */
@Mapper
public interface VoiceRoomMapper extends BaseMapper<VoiceRoom> {
    int markOpen(@Param("id") long id, @Param("providerRoomName") String providerRoomName);
    int markFailed(@Param("id") long id);
    int markClosed(@Param("id") long id, @Param("ownerId") long ownerId);
}
