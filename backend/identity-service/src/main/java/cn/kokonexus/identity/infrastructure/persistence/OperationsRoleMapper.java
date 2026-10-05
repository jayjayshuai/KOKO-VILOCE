package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.OperationsRoleAssignment;
import cn.kokonexus.identity.domain.OperationsRoleAudit;
import cn.kokonexus.identity.domain.UserAccount;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** 运营赋权事务边界，固定 guard 与账号行锁，不使用客户端动态 SQL。 */
public interface OperationsRoleMapper {
    Integer lockGuard();
    UserAccount lockUser(@Param("userId") long userId);
    OperationsRoleAssignment findAssignment(@Param("userId") long userId, @Param("roleCode") String roleCode);
    OperationsRoleAudit findAudit(@Param("requestId") String requestId);
    int validFutureExpiry(@Param("expiresAt") LocalDateTime expiresAt);
    int enabledRole(@Param("roleCode") String roleCode);
    int insertAssignment(OperationsRoleAssignment assignment);
    int updateAssignment(
        @Param("assignment") OperationsRoleAssignment assignment,
        @Param("expectedVersion") long version
    );
    int advanceAuthorityVersion(@Param("userId") long userId, @Param("version") long version);
    int insertAudit(OperationsRoleAudit audit);
}
