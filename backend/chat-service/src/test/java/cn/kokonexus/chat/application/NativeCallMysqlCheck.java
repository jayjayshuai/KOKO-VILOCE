package cn.kokonexus.chat.application;

import cn.kokonexus.chat.persistence.*;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 真实MySQL/生产MP XML/事务代理及同步登记；不启动媒体或使用生产数据。 */
public final class NativeCallMysqlCheck {

    /** 合成网站会话摘要，不能用于网站登录。 */
    private static final String SCOPE = "a".repeat(64),
        OTHER_SCOPE = "b".repeat(64);

    public static void main(String[] args) throws Exception {
        String url = System.getenv("NATIVE_CALL_CHECK_JDBC"),
            user = System.getenv("NATIVE_CALL_CHECK_USER");
        check(
            url != null && url.startsWith("jdbc:mysql://127.0.0.1:33080/koko_native_call_check_20261015?"),
            "Fixed isolated JDBC required"
        );
        check("koko_native_call_check_20261015".equals(user), "Limited isolated user required");
        var source = new DriverManagerDataSource(url, user, System.getenv("NATIVE_CALL_CHECK_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var baseline = Flyway.configure()
            .dataSource(source)
            .table("chat_flyway_schema_history")
            .locations("classpath:db/migration")
            .target("5")
            .load();
        check(baseline.migrate().migrationsExecuted == 5, "Fresh chat V1-V5 baseline required");
        String upgradedConversation = conversation(jdbc, "DIRECT", 901, 902);
        String upgradedCall = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO chat_call(id,conversation_id,initiator_id,client_request_id,request_hash,state,created_at,ring_expires_at,ended_at) VALUES(?,?,?,?,?,'ENDED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
            upgradedCall,
            upgradedConversation,
            901,
            UUID.randomUUID().toString(),
            "c".repeat(64)
        );
        jdbc.update(
            "INSERT INTO chat_call_media_retirement(job_key,call_id,source_version,action,created_at) VALUES(?,?,1,'ROOM',UTC_TIMESTAMP(6))",
            upgradedCall + ":ROOM:1",
            upgradedCall
        );
        var flyway = Flyway.configure()
            .dataSource(source)
            .table("chat_flyway_schema_history")
            .locations("classpath:db/migration")
            .load();
        check(flyway.migrate().migrationsExecuted == 1, "Existing V5 facts should upgrade by exactly V6");
        flyway.validate();
        check(
            jdbc.queryForObject(
                "SELECT attempts=0 AND status='READY' AND lease_token IS NULL FROM chat_call_media_retirement WHERE call_id=?",
                Boolean.class,
                upgradedCall
            ),
            "V5 retirement facts changed on upgrade"
        );
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var sf = factory.getObject();
        for (var type : List.of(MessageMapper.class, BlockMapper.class, ReportMapper.class, ReviewMapper.class))
            if (!sf.getConfiguration().hasMapper(type)) sf.getConfiguration().addMapper(type);
        var sessions = new SqlSessionTemplate(sf);
        var tx = new DataSourceTransactionManager(source);
        var sync = proxy(new ChatSyncWriter(sessions.getMapper(ChatSyncMapper.class)), tx);
        var safety = proxy(
            new ChatSafetyService(
                sessions.getMapper(SafetyLockMapper.class),
                sessions.getMapper(BlockMapper.class),
                sessions.getMapper(ReportMapper.class),
                sessions.getMapper(ReviewMapper.class),
                sessions.getMapper(ConversationMapper.class),
                sessions.getMapper(MemberMapper.class),
                sessions.getMapper(MessageMapper.class),
                sync
            ),
            tx
        );
        var calls = proxy(
            new NativeCallService(
                sessions.getMapper(ConversationMapper.class),
                sessions.getMapper(MemberMapper.class),
                sessions.getMapper(NativeCallMapper.class),
                safety,
                sync
            ),
            tx
        );
        var groups = new java.util.ArrayList<String>();
        groups.add("REAL_V5_FACTS_V6_UPGRADE_PRESERVES_PENDING_RETIREMENT_WITH_FLYWAY_VALIDATION");

        String direct = conversation(jdbc, "DIRECT", 1, 2);
        String request = UUID.randomUUID().toString();
        var ring = calls.invite(1, SCOPE, direct, request, null);
        check(ring.state().equals("RINGING") && ring.version() == 1, "Initial invitation incorrect");
        check(
            ring
                .participants()
                .stream()
                .filter(p -> p.state().equals("ACCEPTED"))
                .count() == 1,
            "Invite auto-accepted recipient"
        );
        check(
            jdbc.queryForObject(
                "SELECT media_identity IS NULL AND website_scope IS NULL FROM chat_call_participant WHERE call_id=? AND user_id=2",
                Boolean.class,
                ring.id()
            ),
            "Invited user got media identity"
        );
        check(calls.invite(1, SCOPE, direct, request, null).equals(ring), "Original invite did not recover");
        expect(IllegalStateException.class, () -> calls.invite(1, OTHER_SCOPE, direct, request, null));
        expect(ResourceNotFoundException.class, () -> calls.read(3, ring.id()));
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(ring);
        check(!serialized.contains(SCOPE), "View leaked website scope");
        groups.add("DIRECT_ORIGINAL_INVITE_ONE_CALL_EXPLICIT_CONSENT_NO_RECIPIENT_IDENTITY_NO_SCOPE_LEAK");

        String accept = UUID.randomUUID().toString();
        var active = calls.command(2, SCOPE, ring.id(), accept, 1, "ACCEPT");
        check(active.current().state().equals("ACTIVE") && active.appliedVersion() == 2, "Acceptance not persisted");
        check(
            calls.command(2, SCOPE, ring.id(), accept, 1, "ACCEPT").appliedVersion() == 2,
            "Original accept changed version"
        );
        expect(IllegalStateException.class, () -> calls.command(2, SCOPE, ring.id(), accept, 1, "DECLINE"));
        expect(IllegalStateException.class, () ->
            calls.command(2, SCOPE, ring.id(), UUID.randomUUID().toString(), 1, "LEAVE")
        );
        calls.heartbeat(2, SCOPE, ring.id());
        expect(IllegalStateException.class, () -> calls.heartbeat(2, OTHER_SCOPE, ring.id()));
        var ended = calls.command(2, SCOPE, ring.id(), UUID.randomUUID().toString(), 2, "LEAVE");
        check(ended.current().state().equals("ENDED"), "Direct hangup did not end call");
        var oldAccept = calls.command(2, SCOPE, ring.id(), accept, 1, "ACCEPT");
        check(
            oldAccept.appliedVersion() == 2 && oldAccept.current().state().equals("ENDED"),
            "Old accept receipt revived call"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_call_participant WHERE call_id=? AND busy_user_id IS NOT NULL",
                Integer.class,
                ring.id()
            ) == 0,
            "Hangup retained busy slots"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_call_media_retirement WHERE call_id=? AND action='ROOM'",
                Integer.class,
                ring.id()
            ) == 1,
            "Hangup lost durable media close intent"
        );
        groups.add("ACCEPT_RECEIPT_VERSION_FENCE_SCOPE_HEARTBEAT_HANGUP_RELEASE_AND_DURABLE_ROOM_RETIREMENT");

        String group = conversation(jdbc, "GROUP", 3, 4, 5, 6);
        var groupRing = calls.invite(3, SCOPE, group, UUID.randomUUID().toString(), List.of(4L, 5L));
        calls.command(4, SCOPE, groupRing.id(), UUID.randomUUID().toString(), 1, "ACCEPT");
        calls.command(5, SCOPE, groupRing.id(), UUID.randomUUID().toString(), 2, "ACCEPT");
        var left = calls.command(3, SCOPE, groupRing.id(), UUID.randomUUID().toString(), 3, "LEAVE");
        check(
            left.current().state().equals("ACTIVE") &&
                left
                    .current()
                    .participants()
                    .stream()
                    .filter(p -> p.state().equals("ACCEPTED"))
                    .count() == 2,
            "Group caller leaving ended other two peers"
        );
        expect(ResourceNotFoundException.class, () -> calls.read(6, groupRing.id()));
        expect(ForbiddenOperationException.class, () ->
            calls.command(4, SCOPE, groupRing.id(), UUID.randomUUID().toString(), 4, "END")
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_call_media_retirement WHERE call_id=? AND action='PARTICIPANT'",
                Integer.class,
                groupRing.id()
            ) == 1,
            "Group leaving lost participant retirement"
        );
        calls.command(4, SCOPE, groupRing.id(), UUID.randomUUID().toString(), 4, "LEAVE");
        groups.add("GROUP_EXPLICIT_TARGETS_THREE_CONSENTS_CALLER_CAN_LEAVE_TWO_CONTINUE_NONINVITEE_DENIED");

        String busyA = conversation(jdbc, "DIRECT", 7, 8),
            busyB = conversation(jdbc, "DIRECT", 7, 9);
        var first = calls.invite(7, SCOPE, busyA, UUID.randomUUID().toString(), null);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM chat_call", Long.class);
        expect(IllegalStateException.class, () -> calls.invite(9, SCOPE, busyB, UUID.randomUUID().toString(), null));
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_call", Long.class) == before,
            "Busy constraint left partial call"
        );
        calls.command(8, SCOPE, first.id(), UUID.randomUUID().toString(), 1, "DECLINE");
        check(calls.read(7, first.id()).endReason().equals("DECLINED"), "Decline did not end direct call");
        var available = calls.invite(9, SCOPE, busyB, UUID.randomUUID().toString(), null);
        calls.command(9, SCOPE, available.id(), UUID.randomUUID().toString(), 1, "END");
        groups.add("GLOBAL_GENERATED_BUSY_UNIQUE_ROLLBACK_REJECTION_AND_RELEASE_AFTER_DECLINE");

        String expired = conversation(jdbc, "DIRECT", 10, 11);
        var timed = calls.invite(10, SCOPE, expired, UUID.randomUUID().toString(), null);
        jdbc.update("UPDATE chat_call SET ring_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", timed.id());
        expect(IllegalStateException.class, () ->
            calls.command(11, SCOPE, timed.id(), UUID.randomUUID().toString(), 1, "ACCEPT")
        );
        calls.reconcile(timed.id());
        check(calls.read(10, timed.id()).endReason().equals("NO_ANSWER"), "Expired call not reconciled");
        String rejoin = conversation(jdbc, "GROUP", 12, 13, 14);
        var old = calls.invite(12, SCOPE, rejoin, UUID.randomUUID().toString(), List.of(13L));
        jdbc.update(
            "UPDATE chat_member SET id=? WHERE conversation_id=? AND user_id=13",
            UUID.randomUUID().toString(),
            rejoin
        );
        expect(ResourceNotFoundException.class, () ->
            calls.command(13, SCOPE, old.id(), UUID.randomUUID().toString(), 1, "ACCEPT")
        );
        calls.reconcile(old.id());
        check(calls.read(12, old.id()).endReason().equals("PERMISSION_CHANGED"), "Rejoin epoch not invalidated");
        groups.add("DATABASE_UTC_RING_EXPIRY_AND_MEMBER_UUID_REJOIN_FENCE_WITHOUT_NEW_MESSAGE_SEQUENCE");

        String blocked = conversation(jdbc, "DIRECT", 15, 16);
        var offered = calls.invite(15, SCOPE, blocked, UUID.randomUUID().toString(), null);
        jdbc.update("UPDATE chat_contact_lock SET high_blocks_low=1 WHERE low_id=15 AND high_id=16");
        expect(ForbiddenOperationException.class, () ->
            calls.command(16, SCOPE, offered.id(), UUID.randomUUID().toString(), 1, "ACCEPT")
        );
        calls.command(15, SCOPE, offered.id(), UUID.randomUUID().toString(), 1, "END");
        expect(ForbiddenOperationException.class, () ->
            calls.invite(15, SCOPE, blocked, UUID.randomUUID().toString(), null)
        );
        groups.add("BIDIRECTIONAL_BLOCK_REJECTS_NEW_INVITE_AND_ACCEPT_BUT_NEVER_PREVENTS_HANGUP");

        String sql = conversation(jdbc, "DIRECT", 17, 18);
        var unconfirmed = calls.invite(17, SCOPE, sql, UUID.randomUUID().toString(), null);
        calls.command(18, SCOPE, unconfirmed.id(), UUID.randomUUID().toString(), 1, "ACCEPT");
        jdbc.execute(
            "CREATE TRIGGER reject_native_retire BEFORE INSERT ON chat_call_media_retirement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic retirement rejection'"
        );
        String hangup = UUID.randomUUID().toString();
        expect(RuntimeException.class, () -> calls.command(18, SCOPE, unconfirmed.id(), hangup, 2, "LEAVE"));
        check(calls.read(17, unconfirmed.id()).state().equals("ACTIVE"), "Failed retirement committed call end");
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_call_command WHERE call_id=? AND request_id=?",
                Integer.class,
                unconfirmed.id(),
                hangup
            ) == 0,
            "Failed retirement committed command receipt"
        );
        jdbc.execute("DROP TRIGGER reject_native_retire");
        calls.command(18, SCOPE, unconfirmed.id(), hangup, 2, "LEAVE");
        groups.add("MEDIA_RETIREMENT_INSERT_FAILURE_ROLLS_BACK_STATE_BUSY_AND_ORIGINAL_COMMAND_RETRY");

        String rollback = conversation(jdbc, "DIRECT", 19, 20);
        var pending = calls.invite(19, SCOPE, rollback, UUID.randomUUID().toString(), null);
        jdbc.execute(
            "CREATE TRIGGER reject_native_receipt BEFORE INSERT ON chat_call_command FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic receipt rejection'"
        );
        String original = UUID.randomUUID().toString();
        expect(RuntimeException.class, () -> calls.command(20, SCOPE, pending.id(), original, 1, "ACCEPT"));
        check(calls.read(19, pending.id()).version() == 1, "Failed receipt committed acceptance");
        jdbc.execute("DROP TRIGGER reject_native_receipt");
        calls.command(20, SCOPE, pending.id(), original, 1, "ACCEPT");
        jdbc.update(
            "UPDATE chat_call_participant SET last_seen_at=UTC_TIMESTAMP(6)-INTERVAL 36 SECOND WHERE call_id=? AND user_id=20",
            pending.id()
        );
        expect(IllegalStateException.class, () -> calls.heartbeat(20, SCOPE, pending.id()));
        calls.reconcile(pending.id());
        check(calls.read(19, pending.id()).endReason().equals("PEER_OFFLINE"), "Expired accepted lease resurrected");
        groups.add("RECEIPT_FAILURE_ROLLBACK_AND_LATE_HEARTBEAT_CANNOT_RESURRECT_EXPIRED_ACCEPTANCE");

        String parallel = conversation(jdbc, "DIRECT", 21, 22);
        String common = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(8);
        var barrier = new CyclicBarrier(8);
        var ids = new java.util.HashSet<String>();
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int n = 0; n < 8; n++) results.add(
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return calls.invite(21, SCOPE, parallel, common, null).id();
                })
            );
            for (var item : results) ids.add(item.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "Concurrent fixture threads not stopped");
        }
        check(ids.size() == 1, "Parallel original invites created duplicate calls");
        var same = calls.read(21, ids.iterator().next());
        calls.command(21, SCOPE, same.id(), UUID.randomUUID().toString(), 1, "END");
        groups.add("EIGHT_PARALLEL_ORIGINAL_INVITES_ONE_DURABLE_CALL_AND_NONDUPLICATE_SYNC_FACTS");

        String privateGroup = conversation(jdbc, "GROUP", 25, 26, 27, 28);
        var privateCall = calls.invite(25, SCOPE, privateGroup, UUID.randomUUID().toString(), List.of(26L, 27L));
        check(
            calls.current(26).id().equals(privateCall.id()) && calls.current(28) == null,
            "Incoming call leaked to uninvited member"
        );
        expect(ForbiddenOperationException.class, () -> calls.mediaGrant(25, SCOPE, privateCall.id()));
        calls.command(26, SCOPE, privateCall.id(), UUID.randomUUID().toString(), 1, "ACCEPT");
        var grant = calls.mediaGrant(25, SCOPE, privateCall.id());
        check(calls.mediaAllows(25, SCOPE, privateCall.id(), grant.identity()), "Valid accepted identity denied");
        check(
            !calls.mediaAllows(25, OTHER_SCOPE, privateCall.id(), grant.identity()),
            "Another website inherited consent"
        );
        check(
            !calls.mediaAllows(25, SCOPE, privateCall.id(), UUID.randomUUID().toString()),
            "Another media identity inherited consent"
        );
        check(
            !calls.mediaAllows(27, SCOPE, privateCall.id(), grant.identity()),
            "Invited user received media before acceptance"
        );
        calls.command(27, SCOPE, privateCall.id(), UUID.randomUUID().toString(), 2, "ACCEPT");
        check(calls.retireWebsite(25, OTHER_SCOPE), "Old unrelated website retirement failed");
        check(
            calls.mediaAllows(25, SCOPE, privateCall.id(), grant.identity()),
            "Unrelated website retired current consent"
        );
        check(calls.retireWebsite(25, SCOPE), "Website retirement not committed");
        check(!calls.mediaAllows(25, SCOPE, privateCall.id(), grant.identity()), "Retired website kept media");
        check(calls.read(26, privateCall.id()).state().equals("ACTIVE"), "Remaining two callers were ended");
        check(calls.current(25) == null, "Retired caller still busy");
        calls.retireWebsite(26, SCOPE);
        check(calls.read(27, privateCall.id()).state().equals("ENDED"), "One remaining participant should end call");
        check(calls.retireWebsite(26, SCOPE), "Original retirement should remain idempotent");
        groups.add("CURRENT_INVITE_PRIVACY_EXPLICIT_MEDIA_CONSENT_IDENTITY_WEBSITE_FENCE_AND_GROUP_LOGOUT_RETIREMENT");

        var retirements = proxy(
            new NativeCallRetirementState(sessions.getMapper(NativeCallRetirementMapper.class)),
            tx
        );
        // 本组仅消费自己的最后一个通话任务，保留前面测试产生的历史事实。
        jdbc.update("UPDATE chat_call_media_retirement SET status='DONE' WHERE call_id<>?", privateCall.id());
        var job = retirements.claim();
        check(job != null && job.getCallId().equals(privateCall.id()), "Original retirement not claimed");
        check(retirements.claim() != null, "Distinct pending job should be claimable");
        String key = job.getJobKey();
        jdbc.update(
            "UPDATE chat_call_media_retirement SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_key=?",
            key
        );
        var renewed = retirements.claim();
        check(
            renewed != null && renewed.getJobKey().equals(key) && !renewed.getLeaseToken().equals(job.getLeaseToken()),
            "Expired work lease not renewed"
        );
        check(!retirements.confirmed(job) && !retirements.failed(job), "Old lease overwrote new work");
        check(retirements.confirmed(renewed), "Current lease confirmation failed");
        check(
            "READY".equals(
                jdbc.queryForObject("SELECT status FROM chat_call_media_retirement WHERE job_key=?", String.class, key)
            ),
            "Early SFU success skipped drain window"
        );
        jdbc.update(
            "UPDATE chat_call_media_retirement SET created_at=UTC_TIMESTAMP(6)-INTERVAL 76 SECOND,next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_key=?",
            key
        );
        var finalJob = retirements.claim();
        check(finalJob != null && finalJob.getJobKey().equals(key), "Drained original target missing");
        check(retirements.confirmed(finalJob), "Final drain confirmation failed");
        check(
            "DONE".equals(
                jdbc.queryForObject("SELECT status FROM chat_call_media_retirement WHERE job_key=?", String.class, key)
            ),
            "Drained success not persisted"
        );
        jdbc.update(
            "UPDATE chat_call_media_retirement SET status='READY',attempts=9,next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_key=?",
            key
        );
        var deadJob = retirements.claim();
        check(retirements.failed(deadJob), "Tenth failure not recorded");
        check(
            "DEAD".equals(
                jdbc.queryForObject("SELECT status FROM chat_call_media_retirement WHERE job_key=?", String.class, key)
            ),
            "Exhausted failure not dead"
        );
        groups.add("RETIREMENT_REAL_SQL_EXPIRED_LEASE_RECLAIM_STALE_CAS_DRAIN_WINDOW_AND_BOUNDED_FAILURE");
        check(calls.scan(null).isEmpty(), "Ended fixture calls remain in active scan");
        System.out.println(
            new ObjectMapper().writeValueAsString(
                Map.of(
                    "passed",
                    true,
                    "realMysql",
                    true,
                    "migrations",
                    6,
                    "groups",
                    groups,
                    "mediaTested",
                    false,
                    "publicRoomsCreated",
                    0,
                    "externalDiscordCalls",
                    0
                )
            )
        );
    }

    private static String conversation(JdbcTemplate jdbc, String type, long... users) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO chat_conversation(id,kind,owner_id,title,last_seq,status) VALUES(?,?,?,?,0,'ACTIVE')",
            id,
            type,
            users[0],
            "Synthetic call conversation"
        );
        for (long user : users)
            jdbc.update(
                "INSERT INTO chat_member(id,conversation_id,user_id,display_name,handle,joined_seq,read_seq) VALUES(?,?,?,?,?,0,0)",
                UUID.randomUUID().toString(),
                id,
                user,
                "Synthetic " + user,
                "native_" + user
            );
        return id;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, DataSourceTransactionManager tx) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable run) {
        try {
            run.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new IllegalStateException("Unexpected rejection type");
        }
        throw new IllegalStateException("Expected rejection missing");
    }
}
