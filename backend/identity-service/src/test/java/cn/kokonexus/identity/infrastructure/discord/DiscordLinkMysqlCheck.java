package cn.kokonexus.identity.infrastructure.discord;

import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.identity.application.DiscordLinkApplicationService;
import cn.kokonexus.identity.application.DiscordLinkConflictException;
import cn.kokonexus.identity.infrastructure.persistence.DiscordLinkMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 真MySQL/Flyway/生产XML/事务/加密及环回HTTP；不使用生产库或真实Discord账户。 */
public final class DiscordLinkMysqlCheck {

    /** 固定隔离检查端口与schema，防环境变量误指向生产。 */
    private static final String PREFIX = "jdbc:mysql://127.0.0.1:33079/koko_discord_check_20261015?";
    /** 合成网站会话作用域，不是实际网站凭据。 */
    private static final String SCOPE = "a".repeat(64),
        OTHER_SCOPE = "b".repeat(64);

    public static void main(String[] args) throws Exception {
        String url = System.getenv("DISCORD_CHECK_JDBC"),
            user = System.getenv("DISCORD_CHECK_USER");
        check(url != null && url.startsWith(PREFIX), "Fixed isolated JDBC required");
        check("koko_discord_check_20261015".equals(user), "Limited isolated user required");
        var source = new DriverManagerDataSource(url, user, System.getenv("DISCORD_CHECK_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var flyway = Flyway.configure()
            .dataSource(source)
            .table("identity_flyway_schema_history")
            .locations("classpath:db/migration")
            .load();
        check(flyway.migrate().migrationsExecuted == 19, "Fresh identity V1-V19 migration required");
        flyway.validate();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver().getResources("classpath:mapper/DiscordLinkMapper.xml")
        );
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(DiscordLinkMapper.class);
        var manager = new DataSourceTransactionManager(source);
        for (int id = 1; id <= 18; id++) jdbc.update(
            "INSERT INTO identity_user(id,email,password_hash,handle,display_name,status) VALUES(?,?,?,?,?,'ACTIVE')",
            id,
            "discord-check-" + id + "@example.invalid",
            "synthetic-not-a-login-hash",
            "discord_check_" + id,
            "Synthetic actor " + id
        );
        var settings = DiscordSecurityPrimitivesTest.settings();
        var cipher = new DiscordTokenCipher(settings.getEncryptionKeyId(), settings.keyRing());
        var exchangeCount = new AtomicInteger();
        var profileCount = new AtomicInteger();
        var externalId = new AtomicReference<>("123456789012345678");
        var access = UUID.randomUUID().toString();
        var refresh = UUID.randomUUID().toString();
        var sentVerifier = new AtomicReference<String>();
        var serverGate = new AtomicReference<CountDownLatch>();
        var arrived = new AtomicReference<CountDownLatch>();
        var failureStatus = new AtomicInteger(0);
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var httpThreads = Executors.newCachedThreadPool();
        http.setExecutor(httpThreads);
        var json = new ObjectMapper().findAndRegisterModules();
        http.createContext("/", exchange -> {
            try (exchange) {
                byte[] response;
                if (exchange.getRequestURI().getPath().equals("/oauth2/token")) {
                    exchangeCount.incrementAndGet();
                    var parameters = DiscordSecurityPrimitivesTest.decode(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    );
                    sentVerifier.set(parameters.get("code_verifier"));
                    if (arrived.get() != null) arrived.get().countDown();
                    if (serverGate.get() != null) try {
                        check(serverGate.get().await(10, TimeUnit.SECONDS), "Provider gate timed out");
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Stopped");
                    }
                    response = json.writeValueAsBytes(
                        Map.of(
                            "access_token",
                            access,
                            "refresh_token",
                            refresh,
                            "scope",
                            "identify",
                            "token_type",
                            "Bearer",
                            "expires_in",
                            604800
                        )
                    );
                } else {
                    profileCount.incrementAndGet();
                    response = json.writeValueAsBytes(Map.of("id", externalId.get(), "username", "synthetic_discord"));
                }
                int status = failureStatus.get() == 0 ? 200 : failureStatus.get();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        http.start();
        var client = new DiscordOAuthClient(
            settings,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            URI.create("http://localhost:" + http.getAddress().getPort() + "/")
        );
        var service = new DiscordLinkApplicationService(mapper, client, cipher, manager);
        var groups = new java.util.ArrayList<String>();
        try {
            String request = UUID.randomUUID().toString();
            var start = service.begin("1", SCOPE, request);
            var repeat = service.begin("1", SCOPE, request);
            check(start.equals(repeat), "Original start response not recoverable");
            var state = query(start.authorizationUrl()).get("state");
            check(
                jdbc.queryForObject(
                    "SELECT generation FROM identity_discord_link_cursor WHERE user_id=1",
                    Long.class
                ) == 1L,
                "Start replay advanced fence"
            );
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM identity_discord_link_audit WHERE user_id=1",
                    Integer.class
                ) == 1,
                "Start replay audited twice"
            );
            var encrypted = jdbc.queryForMap(
                "SELECT encrypted_state,encrypted_verifier FROM identity_discord_oauth_attempt WHERE user_id=1"
            );
            check(!encrypted.get("encrypted_state").equals(state), "State stored in plaintext");
            expect(ForbiddenOperationException.class, () -> service.begin("1", OTHER_SCOPE, request));
            expect(ForbiddenOperationException.class, () -> service.complete("2", SCOPE, state, "synthetic-code"));
            expect(ForbiddenOperationException.class, () ->
                service.complete("1", OTHER_SCOPE, state, "synthetic-code")
            );
            check(exchangeCount.get() == 0, "Invalid scope consumed provider code");
            groups.add("ORIGINAL_START_REPLAY_SAME_NONCE_ONE_GENERATION_ONE_AUDIT_USER_AND_SESSION_ISOLATION");

            var complete = service.complete("1", SCOPE, state, "synthetic-code");
            check("BOUND".equals(complete.status()), "Real binding not confirmed");
            check(exchangeCount.get() == 1 && profileCount.get() == 1, "Expected one token and profile call");
            check(
                sentVerifier
                    .get()
                    .equals(
                        cipher.decrypt(
                            "1",
                            DiscordTokenCipher.Purpose.OAUTH_VERIFIER,
                            encrypted.get("encrypted_verifier").toString()
                        )
                    ),
                "Exchange verifier mismatch"
            );
            check(
                service.complete("1", SCOPE, state, "synthetic-code").status().equals("BOUND"),
                "Lost response receipt not recovered"
            );
            check(exchangeCount.get() == 1, "Bound replay re-exchanged code");
            expect(DiscordLinkConflictException.class, () -> service.complete("1", SCOPE, state, "different-code"));
            expect(DiscordLinkConflictException.class, () -> service.begin("1", SCOPE, UUID.randomUUID().toString()));
            String stored = jdbc.queryForObject(
                "SELECT encrypted_refresh_token FROM identity_discord_connection WHERE user_id=1",
                String.class
            );
            check(
                !stored.equals(refresh) &&
                    cipher.decrypt("1", DiscordTokenCipher.Purpose.REFRESH_TOKEN, stored).equals(refresh),
                "Refresh token not protected"
            );
            check(
                jdbc.queryForObject(
                    "SELECT encrypted_refresh_token IS NULL AND encrypted_verifier IS NULL AND encrypted_state IS NULL FROM identity_discord_oauth_attempt WHERE user_id=1",
                    Boolean.class
                ),
                "Bound attempt retained duplicate secret"
            );
            groups.add(
                "REAL_EXCHANGE_VERIFIER_USER_READ_BIND_ENCRYPTION_LOST_REPLY_RECEIPT_NO_REEXCHANGE_OR_SILENT_REBIND"
            );

            var conflict = service.begin("2", SCOPE, UUID.randomUUID().toString());
            var conflictResult = service.complete(
                "2",
                SCOPE,
                query(conflict.authorizationUrl()).get("state"),
                "conflict-code"
            );
            check(
                conflictResult.status().equals("CONFLICT") && conflictResult.connection() == null,
                "Foreign Discord identity overwritten"
            );
            check(service.connection("1").discordUserId().equals(externalId.get()), "Original owner link changed");
            check(
                jdbc.queryForObject(
                    "SELECT encrypted_refresh_token IS NOT NULL FROM identity_discord_oauth_attempt WHERE user_id=2",
                    Boolean.class
                ),
                "Conflict compensation material lost"
            );
            groups.add("UNIQUE_DISCORD_ID_CONFLICT_ROLLS_BACK_FOREIGN_BINDING_RETAINS_PROTECTED_RECOVERY_MATERIAL");

            externalId.set("223456789012345678");
            var concurrent = service.begin("3", SCOPE, UUID.randomUUID().toString());
            String concurrentState = query(concurrent.authorizationUrl()).get("state");
            int baseline = exchangeCount.get();
            serverGate.set(new CountDownLatch(1));
            arrived.set(new CountDownLatch(1));
            var worker = CompletableFuture.supplyAsync(() ->
                service.complete("3", SCOPE, concurrentState, "parallel-code")
            );
            check(arrived.get().await(5, TimeUnit.SECONDS), "First exchange did not reach HTTP");
            check(
                service.complete("3", SCOPE, concurrentState, "parallel-code").status().equals("EXCHANGING"),
                "Concurrent callback was reclaimed"
            );
            check(exchangeCount.get() == baseline + 1, "Duplicate provider exchange");
            serverGate.get().countDown();
            check(worker.get(10, TimeUnit.SECONDS).status().equals("BOUND"), "Original claimant did not bind");
            serverGate.set(null);
            arrived.set(null);
            groups.add("CONCURRENT_CALLBACK_EXACTLY_ONE_APPLICATION_EXCHANGE_DURABLE_EXCHANGING_RECEIPT");

            externalId.set("323456789012345678");
            var stale = service.begin("4", SCOPE, UUID.randomUUID().toString());
            String staleState = query(stale.authorizationUrl()).get("state");
            serverGate.set(new CountDownLatch(1));
            arrived.set(new CountDownLatch(1));
            var oldWorker = CompletableFuture.supplyAsync(() -> service.complete("4", SCOPE, staleState, "old-code"));
            check(arrived.get().await(5, TimeUnit.SECONDS), "Old exchange did not reach HTTP");
            var newer = service.begin("4", SCOPE, UUID.randomUUID().toString());
            check(!newer.requestId().equals(stale.requestId()), "Replacement did not create new intent");
            serverGate.get().countDown();
            check(
                oldWorker.get(10, TimeUnit.SECONDS).status().equals("SUPERSEDED"),
                "Late callback crossed generation fence"
            );
            check(service.connection("4") == null, "Stale callback created binding");
            serverGate.set(null);
            arrived.set(null);
            groups.add("IN_FLIGHT_OLD_PROVIDER_REPLY_FENCED_BY_NEW_AUTHORIZATION_NO_AUTOMATIC_REVOKE_OF_NEW_GRANTS");

            var expired = service.begin("5", SCOPE, UUID.randomUUID().toString());
            baseline = exchangeCount.get();
            jdbc.update(
                "UPDATE identity_discord_oauth_attempt SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE user_id=5"
            );
            check(
                service
                    .complete("5", SCOPE, query(expired.authorizationUrl()).get("state"), "expired-code")
                    .status()
                    .equals("EXPIRED"),
                "Expired nonce accepted"
            );
            check(exchangeCount.get() == baseline, "Expired code sent externally");
            for (int n = 0; n < 5; n++) service.begin("6", SCOPE, UUID.randomUUID().toString());
            expect(DiscordLinkConflictException.class, () -> service.begin("6", SCOPE, UUID.randomUUID().toString()));
            check(
                jdbc.queryForObject(
                    "SELECT generation FROM identity_discord_link_cursor WHERE user_id=6",
                    Long.class
                ) == 5L,
                "Rejected rate limit advanced generation"
            );
            groups.add("DATABASE_UTC_EXPIRY_NO_PROVIDER_CALL_AND_SERIALIZED_FIVE_STARTS_PER_TEN_MINUTES");

            externalId.set("423456789012345678");
            var rollback = service.begin("7", SCOPE, UUID.randomUUID().toString());
            String rollbackState = query(rollback.authorizationUrl()).get("state");
            jdbc.execute(
                "CREATE TRIGGER discord_reject_bound_audit BEFORE INSERT ON identity_discord_link_audit FOR EACH ROW BEGIN IF NEW.action='BOUND' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit rejection'; END IF; END"
            );
            baseline = exchangeCount.get();
            expect(RuntimeException.class, () -> service.complete("7", SCOPE, rollbackState, "rollback-code"));
            check(service.connection("7") == null, "Audit failure committed binding");
            check(
                service.receipt("7", SCOPE, rollback.requestId()).status().equals("VERIFIED"),
                "Verified recovery material rolled back with final transaction"
            );
            jdbc.execute("DROP TRIGGER discord_reject_bound_audit");
            check(
                service.complete("7", SCOPE, rollbackState, "rollback-code").status().equals("BOUND"),
                "Original verified callback could not finalize"
            );
            check(exchangeCount.get() == baseline + 1, "Verified SQL retry re-exchanged code");
            groups.add("BOUND_AUDIT_FAILURE_ROLLS_BACK_CONNECTION_AND_RECEIPT_SQL_RETRY_WITHOUT_PROVIDER_REEXCHANGE");

            failureStatus.set(503);
            var unknown = service.begin("8", SCOPE, UUID.randomUUID().toString());
            String unknownState = query(unknown.authorizationUrl()).get("state");
            baseline = exchangeCount.get();
            check(
                service.complete("8", SCOPE, unknownState, "unknown-code").status().equals("UNKNOWN"),
                "Unknown exchange reported successful"
            );
            check(
                service.complete("8", SCOPE, unknownState, "unknown-code").status().equals("UNKNOWN"),
                "Unknown receipt changed"
            );
            check(exchangeCount.get() == baseline + 1, "Unknown exchange automatically replayed");
            failureStatus.set(0);
            groups.add("UNKNOWN_EXTERNAL_RESULT_IS_DURABLE_NO_BLIND_EXCHANGE_RETRY");

            jdbc.execute(
                "CREATE TRIGGER discord_reject_start_audit BEFORE INSERT ON identity_discord_link_audit FOR EACH ROW BEGIN IF NEW.user_id=9 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic start rejection'; END IF; END"
            );
            expect(RuntimeException.class, () -> service.begin("9", SCOPE, UUID.randomUUID().toString()));
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM identity_discord_link_cursor WHERE user_id=9",
                    Integer.class
                ) == 0,
                "Audit failure left cursor"
            );
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM identity_discord_oauth_attempt WHERE user_id=9",
                    Integer.class
                ) == 0,
                "Audit failure left attempt"
            );
            jdbc.execute("DROP TRIGGER discord_reject_start_audit");
            var inherited = new TransactionTemplate(manager);
            expect(IllegalStateException.class, () ->
                inherited.execute(tx -> service.begin("10", SCOPE, UUID.randomUUID().toString()))
            );
            check(service.connection("10") == null, "Read-only connection created binding");
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM identity_discord_link_cursor WHERE user_id=10",
                    Integer.class
                ) == 0,
                "Connection read created cursor"
            );
            groups.add("START_AUDIT_ROLLBACK_AND_EXISTING_TRANSACTION_REJECTION_READ_HAS_NO_INSERT");

            var barrier = new CyclicBarrier(8);
            var pool = Executors.newFixedThreadPool(8);
            try {
                String shared = UUID.randomUUID().toString();
                var work = new java.util.ArrayList<java.util.concurrent.Future<String>>();
                for (int i = 0; i < 8; i++) work.add(
                    pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return service.begin("11", SCOPE, shared).authorizationUrl();
                    })
                );
                var results = new java.util.HashSet<String>();
                for (var item : work) results.add(item.get(10, TimeUnit.SECONDS));
                check(results.size() == 1, "Concurrent original starts created different nonce");
                check(
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM identity_discord_oauth_attempt WHERE user_id=11",
                        Integer.class
                    ) == 1,
                    "Concurrent starts inserted duplicates"
                );
            } finally {
                pool.shutdownNow();
            }
            groups.add("EIGHT_CONCURRENT_ORIGINAL_STARTS_ONE_ATTEMPT_ONE_NONCE");
            jdbc.update("UPDATE identity_user SET status='DISABLED' WHERE id=12");
            expect(ForbiddenOperationException.class, () -> service.begin("12", SCOPE, UUID.randomUUID().toString()));
            expect(ForbiddenOperationException.class, () -> service.connection("12"));
            String rowJson = json.writeValueAsString(mapper.selectById(1));
            check(!rowJson.contains(stored), "Connection entity serializes token");
            groups.add("DISABLED_USER_REJECTED_AND_INTERNAL_CIPHERTEXT_NOT_SERIALIZED");

            externalId.set("523456789012345678");
            var retiring = service.begin("13", SCOPE, UUID.randomUUID().toString());
            String retiringState = query(retiring.authorizationUrl()).get("state");
            serverGate.set(new CountDownLatch(1));
            arrived.set(new CountDownLatch(1));
            var retiringWorker = CompletableFuture.supplyAsync(() ->
                service.complete("13", SCOPE, retiringState, "retiring-code")
            );
            check(arrived.get().await(5, TimeUnit.SECONDS), "Retiring exchange not observed");
            service.retireWebsiteSession("13", SCOPE);
            service.retireWebsiteSession("13", SCOPE);
            serverGate.get().countDown();
            check(futureFailure(retiringWorker) instanceof ForbiddenOperationException, "Retired session was accepted");
            serverGate.set(null);
            arrived.set(null);
            check(service.connection("13") == null, "Retired in-flight exchange created binding");
            check(
                jdbc.queryForObject(
                    "SELECT encrypted_refresh_token IS NOT NULL AND attempt_status='SUPERSEDED' FROM identity_discord_oauth_attempt WHERE user_id=13",
                    Boolean.class
                ),
                "Retirement lost protected token or failed to fence"
            );
            check(
                jdbc.queryForObject(
                    "SELECT generation FROM identity_discord_link_cursor WHERE user_id=13",
                    Long.class
                ) == 2L,
                "Repeated retirement changed epoch"
            );
            expect(ForbiddenOperationException.class, () -> service.begin("13", SCOPE, UUID.randomUUID().toString()));
            service.begin("13", OTHER_SCOPE, UUID.randomUUID().toString());
            service.retireWebsiteSession("1", SCOPE);
            check(service.connection("1") != null, "Logout deleted permanent binding");
            groups.add(
                "DURABLE_SESSION_RETIREMENT_FENCES_INFLIGHT_AUTH_PROTECTS_TOKEN_IDEMPOTENT_AND_KEEPS_PERMANENT_BINDING"
            );

            externalId.set("623456789012345678");
            var disabling = service.begin("14", SCOPE, UUID.randomUUID().toString());
            String disablingState = query(disabling.authorizationUrl()).get("state");
            serverGate.set(new CountDownLatch(1));
            arrived.set(new CountDownLatch(1));
            var disabledWorker = CompletableFuture.supplyAsync(() ->
                service.complete("14", SCOPE, disablingState, "disabling-code")
            );
            check(arrived.get().await(5, TimeUnit.SECONDS), "Disabling exchange not observed");
            jdbc.update("UPDATE identity_user SET status='DISABLED' WHERE id=14");
            serverGate.get().countDown();
            check(
                futureFailure(disabledWorker) instanceof ForbiddenOperationException,
                "Disabled in-flight owner was accepted"
            );
            serverGate.set(null);
            arrived.set(null);
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM identity_discord_connection WHERE user_id=14",
                    Integer.class
                ) == 0,
                "Disabled owner bound"
            );
            check(
                jdbc.queryForObject(
                    "SELECT encrypted_refresh_token IS NOT NULL AND attempt_status='SUPERSEDED' FROM identity_discord_oauth_attempt WHERE user_id=14",
                    Boolean.class
                ),
                "Disabled owner lost recovery material"
            );
            jdbc.update("UPDATE identity_user SET status='ACTIVE' WHERE id=14");
            check(
                service.complete("14", SCOPE, disablingState, "disabling-code").status().equals("SUPERSEDED"),
                "Re-enable replayed disabled intent"
            );
            groups.add("ACCOUNT_DISABLED_DURING_EXCHANGE_CANNOT_BIND_RETURNS_PROTECTED_RECOVERY_NO_REPLAY_ON_REENABLE");
            System.out.println(
                json.writeValueAsString(
                    Map.of(
                        "passed",
                        true,
                        "groups",
                        groups,
                        "migrations",
                        19,
                        "actualProvider",
                        false,
                        "realMysql",
                        true,
                        "apiOrSdkMocked",
                        false
                    )
                )
            );
        } finally {
            if (serverGate.get() != null) serverGate.get().countDown();
            http.stop(0);
            httpThreads.shutdownNow();
            check(httpThreads.awaitTermination(5, TimeUnit.SECONDS), "Fixture HTTP threads not stopped");
        }
    }

    private static Map<String, String> query(String url) {
        return DiscordSecurityPrimitivesTest.decode(URI.create(url).getRawQuery());
    }

    private static Throwable futureFailure(CompletableFuture<?> future) throws Exception {
        Throwable result = future.handle((value, failure) -> failure).get(10, TimeUnit.SECONDS);
        check(result != null, "Expected asynchronous rejection");
        while (result.getCause() != null) result = result.getCause();
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new IllegalStateException("Unexpected failure type");
        }
        throw new IllegalStateException("Expected failure did not happen");
    }
}
