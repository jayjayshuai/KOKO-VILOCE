package cn.kokonexus.chat.persistence;

import cn.kokonexus.chat.domain.NativeCallRetirement;
import org.apache.ibatis.annotations.Param;

/** 固定批量一个任务；短事务领取、事务外网络、租约CAS确认。 */
public interface NativeCallRetirementMapper {
    NativeCallRetirement due();
    int claim(@Param("key") String key, @Param("lease") String lease);
    int confirmed(@Param("key") String key, @Param("lease") String lease);
    int failed(@Param("key") String key, @Param("lease") String lease);
}
