package cn.kokonexus.identity.application;

import cn.kokonexus.api.operations.BindingReleaseCommandBinding;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.identity.domain.ReplayConfirmationEntity;
import cn.kokonexus.identity.infrastructure.persistence.BindingReleaseConfirmationMapper;
import cn.kokonexus.identity.infrastructure.persistence.OperationsAuthorityMapper;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 独立动作/表隔离、本人密码、单命令和授权版本确认；通知令牌不可复用。 */
@Service
public class BindingReleaseConfirmationService {

    /** 默认关闭；只有全局/绑定读/绑定恢复同时批准才启用。 */
    private final boolean enabled;
    /** 当前真实身份用例和独立限速事务代理。 */
    private final OperationsAuthorityService authority;
    /** 只读取真实活跃账号和密码/授权版本。 */
    private final OperationsAuthorityMapper users;
    /** 专用短期凭据表。 */
    private final BindingReleaseConfirmationMapper mapper;
    /** 安全随机32字节。 */
    private final SecureRandom random = new SecureRandom();

    public BindingReleaseConfirmationService(
        @Value("${koko.operations.enabled:false}") boolean operationsEnabled,
        @Value("${koko.asset-binding.operations-enabled:false}") boolean readEnabled,
        @Value("${koko.asset-binding.replay-enabled:false}") boolean replayEnabled,
        OperationsAuthorityService authority,
        OperationsAuthorityMapper users,
        BindingReleaseConfirmationMapper mapper
    ) {
        this.enabled = operationsEnabled && readEnabled && replayEnabled;
        this.authority = authority;
        this.users = users;
        this.mapper = mapper;
    }

    /** 仅保存摘要，秘密响应一次，不进入日志；无效命令在密码验证前拒绝。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReplayConfirmation confirm(
        String actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String password
    ) {
        requireEnabled();
        long user = actor(actor);
        session(sessionHash);
        String fingerprint = BindingReleaseCommandBinding.fingerprint(user, domain, command);
        var current = authority.confirmBindingRecoveryCredential(actor, password);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var entity = new ReplayConfirmationEntity();
        entity.setTokenHash(ReplayCommandBinding.sha256(token));
        entity.setUserId(user);
        entity.setSessionHash(sessionHash);
        entity.setCommandHash(fingerprint);
        entity.setAuthorityVersion(current.getOperationsVersion());
        entity.setCredentialFingerprint(ReplayCommandBinding.sha256(current.getPasswordHash()));
        if (mapper.insert(entity) != 1) throw new IllegalStateException("资产恢复确认未保存");
        var saved = mapper.find(entity.getTokenHash());
        if (saved == null) throw new IllegalStateException("资产恢复确认不可读取");
        return new ReplayConfirmation(token, saved.getExpiresAt());
    }

    /** 当前权限/密码版本/会话/动作摘要/到期均再次检查，不能追撤已经授权的在途事务。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public void validate(
        String actor,
        String sessionHash,
        String domain,
        BindingReleaseReplayCommand command,
        String token
    ) {
        requireEnabled();
        long user = actor(actor);
        session(sessionHash);
        String fingerprint = BindingReleaseCommandBinding.fingerprint(user, domain, command);
        authority.requirePermission(actor, "asset:binding:replay");
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw denied();
        var current = users.activeUser(user);
        var saved = mapper.find(ReplayCommandBinding.sha256(token));
        if (
            current == null ||
            !"ACTIVE".equals(current.getStatus()) ||
            saved == null ||
            user != saved.getUserId() ||
            !sessionHash.equals(saved.getSessionHash()) ||
            !fingerprint.equals(saved.getCommandHash()) ||
            !current.getOperationsVersion().equals(saved.getAuthorityVersion()) ||
            !ReplayCommandBinding.sha256(current.getPasswordHash()).equals(saved.getCredentialFingerprint())
        ) throw denied();
    }

    /** 开关关闭时不访问新表；每批只清已过期秘密，绝不删除恢复审计。 */
    @Scheduled(fixedDelay = 60000)
    public void cleanExpired() {
        if (enabled) mapper.deleteExpired(500);
    }

    private void requireEnabled() {
        if (!enabled) throw new OperationsUnavailableException("资产恢复确认未启用");
    }

    private static void session(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("会话绑定无效");
    }

    private static long actor(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("账号无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("账号无效");
        }
    }

    private static OperationsAccessDeniedException denied() {
        return new OperationsAccessDeniedException("资产恢复权限或二次确认已失效");
    }
}
