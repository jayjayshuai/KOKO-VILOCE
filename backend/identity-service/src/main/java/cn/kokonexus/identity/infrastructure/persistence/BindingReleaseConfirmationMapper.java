package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.ReplayConfirmationEntity;
import org.apache.ibatis.annotations.Param;

/** 独立资产确认表，仅存不可逆摘要；通知票据不能跨表查到。 */
public interface BindingReleaseConfirmationMapper {
    int insert(ReplayConfirmationEntity entity);
    ReplayConfirmationEntity find(@Param("tokenHash") String tokenHash);
    int deleteExpired(@Param("limit") int limit);
}
