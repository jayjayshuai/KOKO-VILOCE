package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.ReplayConfirmationEntity;
import cn.kokonexus.identity.domain.UserAccount;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 角色联查、凭据到期与独立限速均使用绑定 SQL；不暴露通用更新/删除角色入口。 */
public interface OperationsAuthorityMapper {
    /** 强制刷新 MyBatis 本地缓存，二次读取必须看到当前密码/授权版本，不受 RR 或一级缓存影响。 */
    UserAccount activeUser(@Param("userId") long userId);
    List<String> roles(@Param("userId") long userId);
    List<String> permissions(@Param("userId") long userId);
    Long lockActiveUser(@Param("userId") long userId);
    int recentLimitReached(@Param("userId") long userId);
    int consumeAttempt(@Param("userId") long userId);
    int insertConfirmation(ReplayConfirmationEntity entity);
    ReplayConfirmationEntity findConfirmation(@Param("tokenHash") String tokenHash);
    /** 只删除已过期确认记录，有索引/批次上限，不删除业务审计或有效凭据。 */
    int deleteExpiredConfirmations(@Param("limit") int limit);
}
