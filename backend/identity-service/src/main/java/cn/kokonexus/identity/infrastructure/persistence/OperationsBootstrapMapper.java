package cn.kokonexus.identity.infrastructure.persistence;

import cn.kokonexus.identity.domain.OperationsBootstrapApproval;
import cn.kokonexus.identity.domain.UserAccount;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** 离线初始化专用读/追加 SQL；不出现在公开 Controller 或 RPC 中。 */
public interface OperationsBootstrapMapper {
    String databaseName();
    String databaseServerId();
    UserAccount inspectUser(@Param("userId") long userId);
    int hasAuthorityHistory();
    int validExpiry(@Param("expiresAt") LocalDateTime expiresAt);
    OperationsBootstrapApproval findApproval(@Param("requestId") String requestId);
    int insertApproval(OperationsBootstrapApproval approval);
}
