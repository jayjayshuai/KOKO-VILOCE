package cn.kokonexus.voice.infrastructure.persistence;

import cn.kokonexus.voice.domain.VoiceMediaPlan.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 房间锁先于绑定锁；任务领取是独立短事务，值参数绑定，复杂CAS在XML。 */
@Mapper
public interface VoiceMediaPlanMapper {
    /** 非锁定有界发现，随后事务代理锁房间并重新核验；cursor避免坏房间饿死后续房间。 */
    List<Long> expiredMemberRooms(@Param("after") long after, @Param("limit") int limit);
    List<Binding> activeBindings(@Param("room") long room);
    Binding binding(@Param("room") long room, @Param("user") long user);
    /** 当前最多100名有效成员批量当前读，避免每次快照N+1。 */
    List<Binding> bindingsForUsers(@Param("room") long room, @Param("users") List<Long> users);
    int insertBinding(@Param("binding") Binding binding);
    int rotateBinding(@Param("binding") Binding binding, @Param("old") long old);
    int insertRetirement(@Param("job") Retirement job);
    List<Retirement> dueExpired(@Param("limit") int limit);
    List<Retirement> duePending(@Param("limit") int limit);
    int claim(@Param("id") String id, @Param("token") String token);
    int exhaustExpired();
    int confirmed(@Param("id") String id, @Param("token") String token);
    int failed(
        @Param("id") String id,
        @Param("token") String token,
        @Param("dead") boolean dead,
        @Param("delay") int delay
    );
    int pending(@Param("room") long room);
    int dead(@Param("room") long room);
}
