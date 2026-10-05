package cn.kokonexus.identity.application;

import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.identity.domain.ReplayConfirmationEntity;
import cn.kokonexus.identity.domain.UserAccount;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 当前有效 RBAC 与命令绑定二次确认；无允许所有账号的降级或权限缓存。 */
@Service
public class OperationsAuthorityService {

    /** 固定能力集合，不从输入拼接权限名称。 */
    private static final Set<String> PERMISSIONS = Set.of(
        "notification:outbox:read",
        "notification:outbox:replay",
        "asset:binding:read",
        "asset:binding:replay",
        "operations:roles:manage"
    );
    /** 会话由认证网关产生的 SHA256，原令牌不经 Dubbo 向业务域传递。 */
    private static final Pattern SESSION_HASH = Pattern.compile("[0-9a-f]{64}");
    /** 原确认随机令牌为 32 字节 base64url 无填充。 */
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_-]{43}");
    /** 密码比较编码器，与注册 BCrypt 协议保持一致。 */
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    /** 强随机秘密凭据来源，不能用事件/账号 ID 推导。 */
    private final SecureRandom random = new SecureRandom();
    /** 服务端运营能力开关，默认关闭且无默认授予用户。 */
    private final boolean enabled;
    /** 持久角色/凭据/限速 Mapper。 */
    private final OperationsAuthorityMapper mapper;
    /** 独立事务限速 Bean，不自调用 REQUIRES_NEW。 */
    private final OperationsCredentialLimiter limiter;

    public OperationsAuthorityService(
        @Value("${koko.operations.enabled:false}") boolean enabled,
        OperationsAuthorityMapper mapper,
        OperationsCredentialLimiter limiter
    ) {
        this.enabled = enabled;
        this.mapper = mapper;
        this.limiter = limiter;
    }

    /** 仅返回指定已认证账号的当前能力；开关关闭返回空集，不初始化或缓存角色。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OperationsAccess access(String userId) {
        long actor = userId(userId);
        if (!enabled) return new OperationsAccess(false, List.of(), List.of());
        activeUser(actor);
        return new OperationsAccess(true, mapper.roles(actor), mapper.permissions(actor));
    }

    /** 读当下权限事实；授权 RPC 失败须传播而非返回允许。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public void requirePermission(String userId, String permission) {
        requireEnabled();
        if (permission == null || !PERMISSIONS.contains(permission)) throw new IllegalArgumentException("未知运营权限");
        long actor = userId(userId);
        activeUser(actor);
        if (!mapper.permissions(actor).contains(permission)) throw denied();
    }

    /** 本人密码通过后保存单命令摘要凭据；错误密码不会回滚另一个 Bean 已提交的限速计数。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReplayConfirmation confirmReplay(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String password
    ) {
        requireEnabled();
        long actor = userId(userId);
        String fingerprint = ReplayCommandBinding.fingerprint(actor, domain, command);
        validateSessionHash(sessionHash);
        UserAccount current = checkedCredential(actor, "notification:outbox:replay", password);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var entity = new ReplayConfirmationEntity();
        entity.setTokenHash(ReplayCommandBinding.sha256(token));
        entity.setUserId(actor);
        entity.setSessionHash(sessionHash);
        entity.setCommandHash(fingerprint);
        entity.setAuthorityVersion(current.getOperationsVersion());
        entity.setCredentialFingerprint(ReplayCommandBinding.sha256(current.getPasswordHash()));
        if (mapper.insertConfirmation(entity) != 1) throw new IllegalStateException("确认凭据未保存");
        ReplayConfirmationEntity saved = mapper.findConfirmation(entity.getTokenHash());
        if (saved == null) throw new IllegalStateException("确认凭据不可读取");
        return new ReplayConfirmation(token, saved.getExpiresAt());
    }

    /** 内部角色管理凭据检查，不通过 RPC 返回密码摘要实体。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserAccount confirmRoleManager(String userId, String password) {
        requireEnabled();
        return checkedCredential(userId(userId), "operations:roles:manage", password);
    }

    private UserAccount checkedCredential(long actor, String permission, String password) {
        UserAccount user = activeUser(actor);
        if (!mapper.permissions(actor).contains(permission)) throw denied();
        limiter.consume(actor);
        boolean passwordMatches = false;
        if (password != null && !password.isBlank() && password.length() <= 72) {
            try {
                passwordMatches = passwords.matches(password, user.getPasswordHash());
            } catch (IllegalArgumentException malformed) {
                /* 仍占确认预算，不输出密码/哈希。 */
            }
        }
        if (!passwordMatches) throw new OperationsAccessDeniedException("本人密码二次确认未通过");
        // 验证期间撤权/密码修改也须拒绝；commit 后变更仍由下一次业务域验证阻断。
        UserAccount current = activeUser(actor);
        if (
            !user.getPasswordHash().equals(current.getPasswordHash()) ||
            !user.getOperationsVersion().equals(current.getOperationsVersion()) ||
            !mapper.permissions(actor).contains(permission)
        ) throw denied();
        return current;
    }

    /** 仅内部资产恢复服务调用，独立权限但共享不可回滚密码尝试预算，不生成通知凭据。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserAccount confirmBindingRecoveryCredential(String userId, String password) {
        requireEnabled();
        return checkedCredential(userId(userId), "asset:binding:replay", password);
    }

    /** 执行前检查账号、当前权限、会话、命令、版本及数据库到期；不能追撤已授权的在途事务。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public void validateReplayConfirmation(
        String userId,
        String sessionHash,
        String domain,
        OutboxReplayCommand command,
        String confirmationToken
    ) {
        requireEnabled();
        long actor = userId(userId);
        validateSessionHash(sessionHash);
        String fingerprint = ReplayCommandBinding.fingerprint(actor, domain, command);
        UserAccount current = activeUser(actor);
        if (
            !mapper.permissions(actor).contains("notification:outbox:replay") ||
            confirmationToken == null ||
            !TOKEN.matcher(confirmationToken).matches()
        ) throw denied();
        ReplayConfirmationEntity saved = mapper.findConfirmation(ReplayCommandBinding.sha256(confirmationToken));
        if (
            saved == null ||
            saved.getUserId() != actor ||
            !sessionHash.equals(saved.getSessionHash()) ||
            !fingerprint.equals(saved.getCommandHash()) ||
            !current.getOperationsVersion().equals(saved.getAuthorityVersion()) ||
            !ReplayCommandBinding.sha256(current.getPasswordHash()).equals(saved.getCredentialFingerprint())
        ) {
            throw denied();
        }
    }

    /** 仅清除索引范围内的已过期秘密快照，一批 500；权限或持久化故障不吞成成功。 */
    @Scheduled(fixedDelay = 60000)
    public void cleanExpiredConfirmations() {
        if (enabled) mapper.deleteExpiredConfirmations(500);
    }

    private UserAccount activeUser(long actor) {
        UserAccount user = mapper.activeUser(actor);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw denied();
        return user;
    }

    private void requireEnabled() {
        if (!enabled) throw new OperationsUnavailableException("运营能力未启用");
    }

    private static void validateSessionHash(String value) {
        if (value == null || !SESSION_HASH.matcher(value).matches()) throw new IllegalArgumentException(
            "会话绑定格式无效"
        );
    }

    private static long userId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("账号标识无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("账号标识无效");
        }
    }

    private static OperationsAccessDeniedException denied() {
        return new OperationsAccessDeniedException("无有效运营权限或二次确认已失效");
    }
}
