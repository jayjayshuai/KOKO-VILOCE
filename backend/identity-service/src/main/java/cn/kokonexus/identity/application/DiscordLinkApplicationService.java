package cn.kokonexus.identity.application;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.identity.domain.DiscordConnectionEntity;
import cn.kokonexus.identity.domain.DiscordLinkCursor;
import cn.kokonexus.identity.domain.DiscordOAuthAttempt;
import cn.kokonexus.identity.infrastructure.discord.DiscordAuthorizationMaterial;
import cn.kokonexus.identity.infrastructure.discord.DiscordOAuthClient;
import cn.kokonexus.identity.infrastructure.discord.DiscordOAuthFailure;
import cn.kokonexus.identity.infrastructure.discord.DiscordTokenCipher;
import cn.kokonexus.identity.infrastructure.persistence.DiscordLinkMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 分段事务控制面；供应商网络调用不持有数据库锁，未知换码结果不可自动再执行。 */
@Service
@ConditionalOnProperty(prefix = "koko.discord", name = "enabled", havingValue = "true")
public class DiscordLinkApplicationService {

    /** 当前授权/连接/审计映射器，共享同一个事务管理器。 */
    private final DiscordLinkMapper mapper;
    /** 固定官方端点的供应商适配器，不提供自动换码重试。 */
    private final DiscordOAuthClient client;
    /** 绑定平台用户和用途的认证加密器。 */
    private final DiscordTokenCipher cipher;
    /** 领取提交、材料保护、身份确认、绑定提交各有独立且明确的事务边界。 */
    private final TransactionTemplate transactions;

    public DiscordLinkApplicationService(
        DiscordLinkMapper mapper,
        DiscordOAuthClient client,
        DiscordTokenCipher cipher,
        PlatformTransactionManager transactionManager
    ) {
        this.mapper = mapper;
        this.client = client;
        this.cipher = cipher;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 原UUID重试只读取原轮次；新UUID创建新围栏，一用户十分钟最多5次意图。 */
    public AuthorizationStart begin(String userId, String websiteScope, String requestId) {
        entry();
        long owner = owner(userId);
        scope(websiteScope);
        requestId = request(requestId);
        final String requested = requestId;
        return transactions.execute(tx -> {
            active(owner);
            requireLiveScope(owner, websiteScope);
            mapper.ensureCursor(owner);
            DiscordLinkCursor cursor = mapper.lockCursor(owner);
            DiscordOAuthAttempt existing = mapper.findRequest(owner, requested);
            if (existing != null) {
                sameScope(existing, websiteScope);
                return start(existing, cursor, userId);
            }
            if (mapper.selectById(owner) != null) throw new DiscordLinkConflictException("请先断开已有Discord绑定");
            if (mapper.recentCount(owner) >= 5) throw new DiscordLinkConflictException("授权尝试过于频繁，请稍后重试");
            if (cursor.getGeneration() == Long.MAX_VALUE) throw new DiscordLinkConflictException("授权轮次已达上限");
            requireOne(mapper.advance(owner, cursor.getGeneration(), requested));
            var material = DiscordAuthorizationMaterial.generate();
            var row = new DiscordOAuthAttempt();
            row.setUserId(owner);
            row.setRequestId(requested);
            row.setWebsiteScope(websiteScope);
            row.setStateDigest(material.getStateDigest());
            row.setGeneration(cursor.getGeneration() + 1);
            row.setAttemptStatus("WAITING");
            row.setEncryptedState(cipher.encrypt(userId, DiscordTokenCipher.Purpose.OAUTH_STATE, material.getState()));
            row.setEncryptedVerifier(
                cipher.encrypt(userId, DiscordTokenCipher.Purpose.OAUTH_VERIFIER, material.getVerifier())
            );
            requireOne(mapper.insertAttempt(row));
            requireOne(mapper.audit(row, "STARTED"));
            return start(mapper.findRequest(owner, requested), mapper.lockCursor(owner), userId);
        });
    }

    /** 单次领取后调用供应商；已VERIFIED的原请求仅重试同库最终提交，不再次换码。 */
    public Receipt complete(String userId, String websiteScope, String state, String code) {
        entry();
        long owner = owner(userId);
        scope(websiteScope);
        String stateDigest = DiscordAuthorizationMaterial.stateDigest(state),
            codeDigest = codeDigest(code);
        Claim claim = transactions.execute(tx -> {
            var cursor = lock(owner);
            requireLiveScope(owner, websiteScope);
            var row = mapper.findState(owner, stateDigest);
            if (row == null) throw new ForbiddenOperationException("授权请求不存在或不属于当前账号");
            sameScope(row, websiteScope);
            if (
                row.getCodeDigest() != null && !row.getCodeDigest().equals(codeDigest)
            ) throw new DiscordLinkConflictException("同一授权请求不能更换授权码");
            String status = effective(row, cursor);
            if (!"WAITING".equals(status)) return new Claim(row.getRequestId(), null, "VERIFIED".equals(status));
            String verifier = cipher.decrypt(
                userId,
                DiscordTokenCipher.Purpose.OAUTH_VERIFIER,
                row.getEncryptedVerifier()
            );
            requireOne(mapper.claim(owner, row.getRequestId(), websiteScope, cursor.getGeneration(), codeDigest));
            requireOne(mapper.audit(row, "EXCHANGE_CLAIMED"));
            return new Claim(row.getRequestId(), verifier, false);
        });
        if (claim.verifier() == null) {
            if (claim.finalizeOnly()) return finish(owner, websiteScope, claim.requestId());
            return receipt(userId, websiteScope, claim.requestId());
        }
        DiscordOAuthClient.Token token;
        try {
            token = client.exchange(code, claim.verifier());
        } catch (DiscordOAuthFailure failure) {
            fail(owner, websiteScope, claim.requestId(), failure);
            return receipt(userId, websiteScope, claim.requestId());
        }
        // 先保护已换取的令牌；这个窗口之前崩溃仍可能产生外部孤儿授权，不能声称跨系统原子性。
        String encrypted = cipher.encrypt(userId, DiscordTokenCipher.Purpose.REFRESH_TOKEN, token.getRefreshToken());
        boolean continuing = transactions.execute(tx -> {
            var cursor = progress(owner);
            var row = mapper.findRequest(owner, claim.requestId());
            sameScope(row, websiteScope);
            requireOne(mapper.storeToken(owner, claim.requestId(), codeDigest, encrypted));
            requireOne(mapper.audit(row, "TOKEN_STORED"));
            boolean allowed =
                "ACTIVE".equals(mapper.lockUser(owner)) &&
                current(row, cursor) &&
                row.isFresh() &&
                !mapper.sessionRetired(owner, websiteScope);
            if (!allowed) {
                requireOne(mapper.settle(owner, claim.requestId(), "EXCHANGING", "SUPERSEDED", "FENCED"));
                requireOne(mapper.audit(row, "SUPERSEDED"));
            }
            return allowed;
        });
        if (!continuing) return receipt(userId, websiteScope, claim.requestId());
        DiscordOAuthClient.User external;
        try {
            external = client.currentUser(token.getAccessToken());
        } catch (DiscordOAuthFailure failure) {
            // 已经换码，401也不能谎称未产生授权；保留受保护令牌待受控核对。
            fail(owner, websiteScope, claim.requestId(), new DiscordOAuthFailure(DiscordOAuthFailure.Kind.UNAVAILABLE));
            return receipt(userId, websiteScope, claim.requestId());
        }
        transactions.executeWithoutResult(tx -> {
            progress(owner);
            var row = mapper.findRequest(owner, claim.requestId());
            sameScope(row, websiteScope);
            requireOne(mapper.verifyIdentity(owner, claim.requestId(), codeDigest, external.id(), external.username()));
            requireOne(mapper.audit(row, "IDENTITY_VERIFIED"));
        });
        return finish(owner, websiteScope, claim.requestId());
    }

    /** 只读当前绑定，不返回密文或令牌；历史UNVERIFIED记录不会被当作新确认绑定。 */
    public Connection connection(String userId) {
        entry();
        long owner = owner(userId);
        return transactions.execute(tx -> {
            active(owner);
            return project(mapper.selectById(owner));
        });
    }

    /** 查询原请求事实不重发外部授权，不自动创建新UUID。 */
    public Receipt receipt(String userId, String websiteScope, String requestId) {
        entry();
        long owner = owner(userId);
        scope(websiteScope);
        final String requested = request(requestId);
        return transactions.execute(tx -> {
            var cursor = lock(owner);
            requireLiveScope(owner, websiteScope);
            var row = mapper.findRequest(owner, requested);
            if (row == null) throw new ForbiddenOperationException("授权请求不存在或不属于当前账号");
            sameScope(row, websiteScope);
            return new Receipt(requested, effective(row, cursor), project(mapper.selectById(owner)));
        });
    }

    /** 后续Gateway注销必须先调用；幂等退役只取消未完成授权，不删除已建立的永久账号绑定。 */
    public void retireWebsiteSession(String userId, String websiteScope) {
        entry();
        long owner = owner(userId);
        scope(websiteScope);
        transactions.executeWithoutResult(tx -> {
            if (mapper.lockUser(owner) == null) throw new ForbiddenOperationException("账号不存在");
            mapper.retireSession(owner, websiteScope);
            var cursor = mapper.lockCursor(owner);
            if (cursor != null && cursor.getActiveRequestId() != null) {
                var row = mapper.findRequest(owner, cursor.getActiveRequestId());
                if (row != null && row.getWebsiteScope().equals(websiteScope)) {
                    requireOne(mapper.invalidateCursor(owner, cursor.getGeneration()));
                    requireOne(mapper.audit(row, "WEBSITE_RETIRED"));
                }
            }
        });
    }

    private Receipt finish(long owner, String websiteScope, String requestId) {
        try {
            return transactions.execute(tx -> {
                var cursor = progress(owner);
                var row = mapper.findRequest(owner, requestId);
                sameScope(row, websiteScope);
                String status = effective(row, cursor);
                if (!"ACTIVE".equals(mapper.lockUser(owner)) && "VERIFIED".equals(row.getAttemptStatus())) {
                    requireOne(mapper.settle(owner, requestId, "VERIFIED", "SUPERSEDED", "ACCOUNT_DISABLED"));
                    requireOne(mapper.audit(row, "SUPERSEDED"));
                    return new Receipt(requestId, "SUPERSEDED", null);
                }
                if ("VERIFIED".equals(status)) {
                    requireOne(mapper.insertConnection(row));
                    requireOne(mapper.bind(owner, requestId, row.getGeneration()));
                    requireOne(mapper.audit(row, "BOUND"));
                    status = "BOUND";
                } else if (
                    "VERIFIED".equals(row.getAttemptStatus()) &&
                    ("SUPERSEDED".equals(status) || "EXPIRED".equals(status))
                ) {
                    requireOne(mapper.settle(owner, requestId, "VERIFIED", "SUPERSEDED", status));
                    requireOne(mapper.audit(row, "SUPERSEDED"));
                    status = "SUPERSEDED";
                }
                return new Receipt(requestId, status, project(mapper.selectById(owner)));
            });
        } catch (DuplicateKeyException conflict) {
            // 唯一绑定冲突使整个绑定事务回滚，再记录原请求事实，绝不覆盖已有账号。
            transactions.executeWithoutResult(tx -> {
                var cursor = lock(owner);
                var row = mapper.findRequest(owner, requestId);
                sameScope(row, websiteScope);
                if ("VERIFIED".equals(row.getAttemptStatus())) {
                    String status = current(row, cursor) ? "CONFLICT" : "SUPERSEDED";
                    requireOne(mapper.settle(owner, requestId, "VERIFIED", status, "BINDING_CONFLICT"));
                    requireOne(mapper.audit(row, status));
                }
            });
            return receipt(String.valueOf(owner), websiteScope, requestId);
        }
    }

    private void fail(long owner, String websiteScope, String requestId, DiscordOAuthFailure failure) {
        transactions.executeWithoutResult(tx -> {
            progress(owner);
            var row = mapper.findRequest(owner, requestId);
            sameScope(row, websiteScope);
            String status = failure.kind() == DiscordOAuthFailure.Kind.DENIED ? "DENIED" : "UNKNOWN";
            requireOne(mapper.settle(owner, requestId, "EXCHANGING", status, failure.kind().name()));
            requireOne(mapper.audit(row, status));
        });
    }

    private DiscordLinkCursor lock(long owner) {
        active(owner);
        return cursor(owner);
    }

    /** 仅保护已发生的外部副作用，停用/退役后也保存密文；不能用于新授权或最终绑定。 */
    private DiscordLinkCursor progress(long owner) {
        if (mapper.lockUser(owner) == null) throw new ForbiddenOperationException("账号不存在");
        return cursor(owner);
    }

    private DiscordLinkCursor cursor(long owner) {
        var cursor = mapper.lockCursor(owner);
        if (cursor == null) throw new ForbiddenOperationException("授权请求不存在或不属于当前账号");
        return cursor;
    }

    private void requireLiveScope(long owner, String websiteScope) {
        if (mapper.sessionRetired(owner, websiteScope)) throw new ForbiddenOperationException(
            "网站会话已退役，请重新登录后发起授权"
        );
    }

    private void active(long owner) {
        if (!"ACTIVE".equals(mapper.lockUser(owner))) throw new ForbiddenOperationException("账号不可用");
    }

    private AuthorizationStart start(DiscordOAuthAttempt row, DiscordLinkCursor cursor, String owner) {
        String status = effective(row, cursor);
        String url = null;
        if ("WAITING".equals(status)) {
            String state = cipher.decrypt(owner, DiscordTokenCipher.Purpose.OAUTH_STATE, row.getEncryptedState());
            String verifier = cipher.decrypt(
                owner,
                DiscordTokenCipher.Purpose.OAUTH_VERIFIER,
                row.getEncryptedVerifier()
            );
            url = client.authorizationUri(state, DiscordAuthorizationMaterial.challenge(verifier)).toString();
        }
        return new AuthorizationStart(row.getRequestId(), status, url, row.getExpiresAt());
    }

    private static String effective(DiscordOAuthAttempt row, DiscordLinkCursor cursor) {
        if (
            "WAITING".equals(row.getAttemptStatus()) ||
            "VERIFIED".equals(row.getAttemptStatus()) ||
            "EXCHANGING".equals(row.getAttemptStatus())
        ) {
            if (!current(row, cursor)) return "SUPERSEDED";
            if (!row.isFresh()) return "EXPIRED";
        }
        return row.getAttemptStatus();
    }

    private static boolean current(DiscordOAuthAttempt row, DiscordLinkCursor cursor) {
        return (
            row.getGeneration().equals(cursor.getGeneration()) && row.getRequestId().equals(cursor.getActiveRequestId())
        );
    }

    private static Connection project(DiscordConnectionEntity row) {
        return row == null
            ? null
            : new Connection(
                  row.getDiscordUserId(),
                  row.getDiscordUsername(),
                  row.getConnectionStatus(),
                  row.getConnectionVersion(),
                  "ACTIVE".equals(row.getConnectionStatus()) ? row.getConnectedAt() : null
              );
    }

    private static void sameScope(DiscordOAuthAttempt row, String scope) {
        if (row == null || !row.getWebsiteScope().equals(scope)) throw new ForbiddenOperationException(
            "授权请求不属于当前网站会话"
        );
    }

    private static void entry() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException(
            "Discord protocol cannot run inside an existing transaction"
        );
    }

    private static long owner(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.parseLong(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("平台用户标识无效");
        }
    }

    private static void scope(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("网站会话作用域无效");
    }

    private static String request(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("授权请求UUID无效");
        }
    }

    private static String codeDigest(String code) {
        if (
            code == null || code.isEmpty() || code.length() > 4096 || !code.chars().allMatch(c -> c >= 33 && c <= 126)
        ) throw new IllegalArgumentException("授权码格式无效");
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void requireOne(int rows) {
        if (rows != 1) throw new IllegalStateException("Discord state transition not confirmed");
    }

    /** 内部领取对象不打印或序列化verifier，不能直接作为接口返回。 */
    private record Claim(
        String requestId,
        @com.fasterxml.jackson.annotation.JsonIgnore String verifier,
        boolean finalizeOnly
    ) {
        @Override
        public String toString() {
            return "DiscordExchangeClaim";
        }
    }

    /** URL仅包含授权nonce与公开挑战；到期时间明确为UTC，非客户端授权凭据。 */
    public record AuthorizationStart(
        @io.swagger.v3.oas.annotations.media.Schema(description = "本次明确绑定意图的UUID") String requestId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "授权事实状态；WAITING才有授权地址") String status,
        @io.swagger.v3.oas.annotations.media.Schema(description = "固定官方授权地址，可空；不包含PKCE verifier")
        String authorizationUrl,
        @io.swagger.v3.oas.annotations.media.Schema(description = "UTC到期时间；客户端时间不能赋权")
        LocalDateTime expiresAtUtc
    ) {
        @Override
        public String toString() {
            return "DiscordAuthorizationStart[" + requestId + "," + status + "]";
        }
    }

    /** 最小连接投影；只有ACTIVE表示本系统已确认绑定，不表示群权限同步完成。 */
    public record Connection(
        @io.swagger.v3.oas.annotations.media.Schema(description = "供应商确认的Discord标识") String discordUserId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "公开用户名，不是登录凭据") String username,
        @io.swagger.v3.oas.annotations.media.Schema(description = "ACTIVE或未验证的历史状态") String status,
        @io.swagger.v3.oas.annotations.media.Schema(description = "绑定的单调授权轮次") long version,
        @io.swagger.v3.oas.annotations.media.Schema(description = "新ACTIVE绑定的UTC时间，历史未验证记录为空")
        LocalDateTime connectedAtUtc
    ) {}

    /** 原请求状态与当前连接分开；旧BOUND收据不能伪称已重新建立当前连接。 */
    public record Receipt(
        @io.swagger.v3.oas.annotations.media.Schema(description = "原请求UUID，不自动创建新请求") String requestId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "原授权事实状态；EXCHANGING不证明成功或失败")
        String status,
        @io.swagger.v3.oas.annotations.media.Schema(description = "当前账号绑定，未绑定时为空；与历史收据分离")
        Connection connection
    ) {}
}
