package cn.kokonexus.identity.application;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.identity.domain.OperationsBootstrapApproval;
import cn.kokonexus.identity.domain.OperationsRoleAssignment;
import cn.kokonexus.identity.domain.OperationsRoleAudit;
import cn.kokonexus.identity.infrastructure.persistence.OperationsBootstrapMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsRoleMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 非 Spring 扫描 Bean，仅显式离线工具构造；不在启动时调用，不暴露 HTTP/RPC。 */
@RequiredArgsConstructor
public class OperationsBootstrapService {

    /** 固定首次角色。 */
    private static final String ADMIN = "OPERATIONS_ADMIN";
    /** 共用的串行 guard、角色/账号版本与审计。 */
    private final OperationsRoleMapper roles;
    /** 审批摘要和当前数据库/账号投影。 */
    private final OperationsBootstrapMapper bootstrap;

    /** 仅检查和返回待明确审批的摘要，不创建账号或授权。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public String plan(String database, OperationsBootstrapCommand input) {
        var command = required(input);
        String hash = fingerprint(database, command);
        if (existing(command, hash) == null) requireInitialTarget(command);
        return hash;
    }

    /** 审批摘要不等同身份票据；调用者必须已拥有该库权限，CLI 是唯一受支持的入口。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OperationsRoleChangeReceipt apply(String database, OperationsBootstrapCommand input, String approval) {
        var command = required(input);
        String hash = fingerprint(database, command);
        if (
            approval == null ||
            !approval.matches("[0-9a-f]{64}") ||
            !MessageDigest.isEqual(
                hash.getBytes(StandardCharsets.US_ASCII),
                approval.getBytes(StandardCharsets.US_ASCII)
            )
        ) {
            throw new OperationsAccessDeniedException("初始化须明确确认原数据库实例与完整命令");
        }
        if (!Integer.valueOf(1).equals(roles.lockGuard())) throw new IllegalStateException("运营 guard 缺失");
        // 同原请求只返回历史事实，账号后来禁用、改标识或角色撤销不会重新赋权。
        var previous = existing(command, hash);
        if (previous != null) return receipt(previous);
        var locked = roles.lockUser(command.userId());
        if (locked == null) throw new IllegalArgumentException("明确指定的账号不存在");
        requireInitialTarget(command);
        if (locked.getOperationsVersion() == Long.MAX_VALUE) throw new IllegalStateException("账号授权版本耗尽");
        var assignment = new OperationsRoleAssignment();
        assignment.setUserId(command.userId());
        assignment.setRoleCode(ADMIN);
        assignment.setStatus("ACTIVE");
        assignment.setVersion(1L);
        assignment.setExpiresAt(command.expiresAt());
        if (
            roles.insertAssignment(assignment) != 1 ||
            roles.advanceAuthorityVersion(command.userId(), locked.getOperationsVersion()) != 1
        ) {
            throw new IllegalStateException("初始化关系或账号版本写入失败");
        }
        var audit = new OperationsRoleAudit();
        audit.setRequestId(command.requestId());
        audit.setSource("SERVER_BOOTSTRAP");
        audit.setUserId(command.userId());
        audit.setRoleCode(ADMIN);
        audit.setExpectedVersion(0L);
        audit.setAcceptedVersion(1L);
        audit.setPreviousStatus("NONE");
        audit.setAcceptedStatus("ACTIVE");
        audit.setExpiresAt(command.expiresAt());
        audit.setReason(command.reason());
        if (roles.insertAudit(audit) != 1) throw new IllegalStateException("初始化审计写入失败");
        var bound = new OperationsBootstrapApproval();
        bound.setRequestId(command.requestId());
        bound.setCommandHash(hash);
        bound.setApprovedHandle(command.expectedHandle());
        if (bootstrap.insertApproval(bound) != 1) throw new IllegalStateException("初始化审批绑定未保存");
        var saved = roles.findAudit(command.requestId());
        if (saved == null) throw new IllegalStateException("初始化审计读取失败");
        return receipt(saved);
    }

    private void requireInitialTarget(OperationsBootstrapCommand command) {
        if (bootstrap.hasAuthorityHistory() != 0) throw new IllegalStateException("已有运营授权历史，禁止再次初始化");
        var user = bootstrap.inspectUser(command.userId());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !command.expectedHandle().equals(user.getHandle())) {
            throw new IllegalArgumentException("账号状态或负责人确认的标识不匹配");
        }
        if (roles.enabledRole(ADMIN) != 1 || bootstrap.validExpiry(command.expiresAt()) != 1) {
            throw new IllegalArgumentException("首次角色须启用且到期时间在未来一天内");
        }
    }

    private OperationsRoleAudit existing(OperationsBootstrapCommand command, String hash) {
        var audit = roles.findAudit(command.requestId());
        var bound = bootstrap.findApproval(command.requestId());
        if (audit == null && bound == null) return null;
        if (
            audit == null ||
            bound == null ||
            !hash.equals(bound.getCommandHash()) ||
            !command.expectedHandle().equals(bound.getApprovedHandle()) ||
            !"SERVER_BOOTSTRAP".equals(audit.getSource()) ||
            audit.getOperatorId() != null ||
            audit.getUserId() != command.userId() ||
            !ADMIN.equals(audit.getRoleCode()) ||
            audit.getExpectedVersion() != 0 ||
            audit.getAcceptedVersion() != 1 ||
            !"NONE".equals(audit.getPreviousStatus()) ||
            !"ACTIVE".equals(audit.getAcceptedStatus()) ||
            !command.expiresAt().equals(audit.getExpiresAt()) ||
            !command.reason().equals(audit.getReason())
        ) {
            throw new IllegalStateException("初始化请求已被其他命令使用或审批历史不完整");
        }
        return audit;
    }

    private String fingerprint(String database, OperationsBootstrapCommand command) {
        if (
            database == null || !database.matches("[a-z][a-z0-9_]{0,63}") || !database.equals(bootstrap.databaseName())
        ) throw new IllegalArgumentException("目标数据库不匹配");
        String server = bootstrap.databaseServerId();
        if (server == null || !server.matches("[0-9a-fA-F-]{36}")) throw new IllegalStateException(
            "数据库实例标识缺失"
        );
        try {
            var bytes = new ByteArrayOutputStream();
            try (var fields = new DataOutputStream(bytes)) {
                fields.writeInt(1);
                for (String value : new String[] {
                    database,
                    server,
                    command.requestId(),
                    command.expectedHandle(),
                    command.expiresAt().toString(),
                    command.reason(),
                }) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    fields.writeInt(encoded.length);
                    fields.write(encoded);
                }
                fields.writeLong(command.userId());
            }
            return ReplayCommandBinding.sha256(java.util.HexFormat.of().formatHex(bytes.toByteArray()));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("初始化审批编码失败", failure);
        }
    }

    private static OperationsBootstrapCommand required(OperationsBootstrapCommand command) {
        if (command == null) throw new IllegalArgumentException("初始化命令缺失");
        return command.normalized();
    }

    private static OperationsRoleChangeReceipt receipt(OperationsRoleAudit audit) {
        return new OperationsRoleChangeReceipt(
            audit.getRequestId(),
            audit.getUserId().toString(),
            audit.getRoleCode(),
            audit.getAcceptedStatus(),
            audit.getAcceptedVersion(),
            audit.getExpiresAt(),
            audit.getCreatedAt()
        );
    }
}
