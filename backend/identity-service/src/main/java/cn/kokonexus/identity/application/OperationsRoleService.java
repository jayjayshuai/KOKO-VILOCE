package cn.kokonexus.identity.application;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.identity.domain.OperationsRoleAssignment;
import cn.kokonexus.identity.domain.OperationsRoleAudit;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsRoleMapper;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 串行运营授权变更及追加审计；不提供首管理员自动注册或本人提权/撤权。 */
@Service
@RequiredArgsConstructor
public class OperationsRoleService {

    /** 与迁移定义一致的固定可管理角色。 */
    private static final Set<String> ROLES = Set.of(
        "OPERATIONS_ADMIN",
        "NOTIFICATION_OPERATOR",
        "ASSET_BINDING_AUDITOR",
        "ASSET_BINDING_RECOVERY",
        "NOTIFICATION_AUDITOR"
    );
    /** 关系/账号版本更新和追加审计映射。 */
    private final OperationsRoleMapper mapper;
    /** 事实授权与独立事务本人密码限速。 */
    private final OperationsAuthorityService authority;
    /** guard 后重新读取当前密码/运营版本，强制刷新 MyBatis 本地缓存。 */
    private final OperationsAuthorityMapper credentials;

    /** 管理者本人确认后串行变更他人角色；关系、账号版本、审计同事务，相同命令只返回历史回执。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OperationsRoleChangeReceipt changeRole(
        String operatorId,
        OperationsRoleChangeCommand command,
        String password
    ) {
        long actor = positiveId(operatorId);
        if (
            command == null ||
            command.roleCode() == null ||
            !ROLES.contains(command.roleCode()) ||
            command.expectedVersion() < 0
        ) throw new IllegalArgumentException("角色变更命令无效");
        long target = positiveId(command.userId());
        if (actor == target) throw new OperationsAccessDeniedException("不允许修改本人的运营角色");
        String requestId = canonicalUuid(command.requestId());
        String reason = command.reason() == null ? "" : command.reason().strip();
        if (reason.length() < 10 || reason.length() > 500 || (!command.enabled() && command.expiresAt() != null)) {
            throw new IllegalArgumentException("变更原因或到期设置无效");
        }
        // DATETIME(6) 不接受纳秒幂等键；拒绝而不是在第一次写入时悄悄截断。
        if (command.expiresAt() != null && command.expiresAt().getNano() % 1000 != 0) {
            throw new IllegalArgumentException("到期时间最多保留六位小数");
        }
        var confirmed = authority.confirmRoleManager(operatorId, password);
        if (!Integer.valueOf(1).equals(mapper.lockGuard())) throw new IllegalStateException("运营授权 guard 不可用");
        authority.requirePermission(operatorId, "operations:roles:manage");
        var current = credentials.activeUser(actor);
        if (
            current == null ||
            !confirmed.getPasswordHash().equals(current.getPasswordHash()) ||
            !confirmed.getOperationsVersion().equals(current.getOperationsVersion())
        ) {
            throw new OperationsAccessDeniedException("管理者确认状态已改变");
        }
        var user = mapper.lockUser(target);
        if (user == null) {
            throw new IllegalArgumentException("目标账号不存在");
        }
        var existing = mapper.findAudit(requestId);
        if (existing != null) {
            if (
                existing.getOperatorId() == null ||
                actor != existing.getOperatorId() ||
                target != existing.getUserId() ||
                !command.roleCode().equals(existing.getRoleCode()) ||
                command.expectedVersion() != existing.getExpectedVersion() ||
                !reason.equals(existing.getReason()) ||
                !Objects.equals(command.expiresAt(), existing.getExpiresAt()) ||
                !state(command.enabled()).equals(existing.getAcceptedStatus())
            ) {
                throw new IllegalStateException("请求标识已用于不同角色命令");
            }
            return receipt(existing);
        }
        // 已受理请求只返回历史事实；后续禁用目标账号不改变既有受理回执，也不会再次赋权。
        if (command.enabled() && !"ACTIVE".equals(user.getStatus())) {
            throw new IllegalArgumentException("目标账号不可授权");
        }
        var previous = mapper.findAssignment(target, command.roleCode());
        long version = previous == null ? 0 : previous.getVersion();
        if (version != command.expectedVersion() || (!command.enabled() && previous == null)) {
            throw new IllegalStateException("角色关系或确认版本已改变");
        }
        if (version == Long.MAX_VALUE || user.getOperationsVersion() == Long.MAX_VALUE) {
            throw new IllegalStateException("运营授权版本已达上限");
        }
        if (
            command.enabled() &&
            (mapper.enabledRole(command.roleCode()) != 1 ||
                (command.expiresAt() != null && mapper.validFutureExpiry(command.expiresAt()) != 1))
        ) {
            throw new IllegalArgumentException("角色未启用或到期时间不在未来一年内");
        }
        var assignment = new OperationsRoleAssignment();
        assignment.setUserId(target);
        assignment.setRoleCode(command.roleCode());
        assignment.setStatus(state(command.enabled()));
        assignment.setVersion(version + 1);
        assignment.setExpiresAt(command.expiresAt());
        int updated =
            previous == null ? mapper.insertAssignment(assignment) : mapper.updateAssignment(assignment, version);
        if (updated != 1 || mapper.advanceAuthorityVersion(target, user.getOperationsVersion()) != 1) {
            throw new IllegalStateException("角色/账号授权版本更新失败");
        }
        var audit = new OperationsRoleAudit();
        audit.setRequestId(requestId);
        audit.setOperatorId(actor);
        audit.setSource("OPERATOR");
        audit.setUserId(target);
        audit.setRoleCode(command.roleCode());
        audit.setExpectedVersion(version);
        audit.setAcceptedVersion(version + 1);
        audit.setPreviousStatus(previous == null ? "NONE" : previous.getStatus());
        audit.setPreviousExpiresAt(previous == null ? null : previous.getExpiresAt());
        audit.setAcceptedStatus(assignment.getStatus());
        audit.setExpiresAt(command.expiresAt());
        audit.setReason(reason);
        try {
            if (mapper.insertAudit(audit) != 1) throw new IllegalStateException("角色审计写入失败");
        } catch (DuplicateKeyException conflict) {
            throw new IllegalStateException("幂等请求或角色版本冲突，变更未提交", conflict);
        }
        var saved = mapper.findAudit(requestId);
        if (saved == null) throw new IllegalStateException("角色审计未保存");
        return receipt(saved);
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

    private static String state(boolean enabled) {
        return enabled ? "ACTIVE" : "REVOKED";
    }

    private static long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("账号标识无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("账号标识无效");
        }
    }

    private static String canonicalUuid(String value) {
        if (
            value == null ||
            !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        ) {
            throw new IllegalArgumentException("受理标识必须为标准 UUID");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
