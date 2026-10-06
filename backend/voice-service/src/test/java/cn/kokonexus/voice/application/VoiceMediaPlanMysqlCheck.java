package cn.kokonexus.voice.application;

import cn.kokonexus.voice.domain.VoiceInteraction.CommandType;
import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.persistence.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 固定本机独立MySQL、生产XML和Spring事务；只合成事实，不发放媒体JWT或访问项目数据库。 */
public class VoiceMediaPlanMysqlCheck {

    /** 固定合成房间。 */ private static final long ROOM = 9301;
    /** 真实当前核心事务代理。 */ private static VoiceInteractionService core;
    /** 合成成员服务器会话，不打印值。 */ private static final Map<Long, String> sessions = new HashMap<>();
    /** 仅固定实验室Schema的SQL。 */ private static JdbcTemplate jdbc;

    public static void main(String[] args) throws Exception {
        String url = System.getenv("VOICE_MEDIA_JDBC"),
            user = System.getenv("VOICE_MEDIA_USER");
        check(
            url != null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:33079/koko_voice_media_check_20261006\\?.+"),
            "Fixed local isolated schema required"
        );
        check("koko_voice_media_check_20261006".equals(user), "Schema-only lab account required");
        var source = new DriverManagerDataSource(url, user, System.getenv("VOICE_MEDIA_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        check(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8.4."), "Actual MySQL8.4 required");
        check(
            jdbc.queryForObject("SELECT CURRENT_USER()", String.class).equals(user + "@127.0.0.1"),
            "Unexpected account"
        );
        expect(
            () -> jdbc.queryForObject("SELECT COUNT(*) FROM mysql.user", Integer.class),
            org.springframework.dao.DataAccessException.class
        );
        var flyway = Flyway.configure()
            .dataSource(source)
            .table("voice_flyway_schema_history")
            .locations("classpath:db/migration")
            .load();
        check(flyway.migrate().migrationsExecuted == 4, "Fresh V1-V4 required");
        flyway.validate();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var sql = new SqlSessionTemplate(factory.getObject());
        var media = sql.getMapper(VoiceMediaPlanMapper.class);
        var recorder = new VoiceMediaPlanRecorder(sql.getMapper(VoiceInteractionMapper.class), media, true);
        var manager = new DataSourceTransactionManager(source);
        core = proxy(new VoiceInteractionService(sql.getMapper(VoiceInteractionMapper.class), true, recorder), manager);
        var closure = proxy(
            new VoiceClosureState(
                sql.getMapper(VoiceRoomMapper.class),
                sql.getMapper(VoiceInteractionMapper.class),
                recorder
            ),
            manager
        );
        var retirement = proxy(new VoiceMediaRetirementState(media), manager);
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,max_participants,control_mode,provider_room_name) VALUES(9301,1,'合成房主','synthetic-media-lab','合成媒体计划','OPEN',100,'CONTROLLED','koko-voice-9301')"
        );
        join(1);
        join(2);
        join(3);
        check(generation(2) == 1 && jobs() == 0, "Initial join must not retire unissued previous identity");
        String audience = identity(2);
        command(1, CommandType.PULL, 1, 2L, null);
        check(
            generation(2) == 2 && !audience.equals(identity(2)),
            "Muted on-mic assignment must rotate seat authorization"
        );
        check(!publish(2) && jobs() == 1, "Muted seat is not publish grant");
        System.out.println(
            "PASS MEDIA_SQL_1 actual V1-V4/FKs/scope account/join/opaque seat generation/same transaction"
        );

        String muted = identity(2),
            id = UUID.randomUUID().toString(),
            version = version();
        var first = core.command(2, ROOM, id, sessions.get(2L), version, CommandType.MUTE, 1, null, null, false);
        var repeat = core.command(2, ROOM, id, sessions.get(2L), version, CommandType.MUTE, 1, null, null, false);
        check(
            first.equals(repeat) && generation(2) == 3 && publish(2) && jobs() == 2,
            "Receipt replay repeated media mutation"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_media_retirement WHERE media_identity=?",
                Integer.class,
                muted
            ) == 1,
            "Old fixed identity task missing"
        );
        String before = identity(2),
            beforeVersion = version();
        long count = jobs();
        String rejectedId = UUID.randomUUID().toString();
        jdbc.execute(
            "CREATE TRIGGER synthetic_fail_retire BEFORE INSERT ON voice_media_retirement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'"
        );
        try {
            expect(
                () ->
                    core.command(
                        2,
                        ROOM,
                        rejectedId,
                        sessions.get(2L),
                        beforeVersion,
                        CommandType.MUTE,
                        1,
                        null,
                        null,
                        true
                    ),
                org.springframework.dao.DataAccessException.class
            );
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_fail_retire");
        }
        check(
            generation(2) == 3 &&
                before.equals(identity(2)) &&
                publish(2) &&
                jobs() == count &&
                beforeVersion.equals(version()),
            "Media failure did not roll back core state/version"
        );
        check(
            !jdbc.queryForObject("SELECT muted FROM voice_seat WHERE room_id=9301 AND seat_no=1", Boolean.class),
            "Seat incorrectly committed on failed media plan"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_command_receipt WHERE request_id=?",
                Integer.class,
                rejectedId
            ) == 0,
            "Failed transaction left receipt"
        );
        System.out.println(
            "PASS MEDIA_SQL_2 original UUID replay; retirement insert fault rolls back seat/binding/version/audit/receipt"
        );

        String raceVersion = version();
        long beforeRaceJobs = jobs();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) results.add(
                pool.submit(() -> {
                    barrier.await();
                    try {
                        core.command(
                            2,
                            ROOM,
                            UUID.randomUUID().toString(),
                            sessions.get(2L),
                            raceVersion,
                            CommandType.MUTE,
                            1,
                            null,
                            null,
                            true
                        );
                        return true;
                    } catch (IllegalStateException conflict) {
                        return false;
                    }
                })
            );
            int wins = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) wins++;
            check(
                wins == 1 && generation(2) == 4 && jobs() == beforeRaceJobs + 1,
                "Concurrent core commands duplicated epoch/retirement"
            );
        }

        command(2, CommandType.DOWN, 1, null, null);
        check(generation(2) == 5 && !publish(2), "Down did not retire publish identity");
        String oldSession = sessions.get(2L);
        command(2, CommandType.LEAVE, null, null, null);
        check(generation(2) == 6, "Leave epoch missing");
        join(2);
        check(generation(2) == 7, "Rejoin reset generation");
        expect(() -> core.heartbeat(2, ROOM, oldSession), cn.kokonexus.common.api.ForbiddenOperationException.class);
        jdbc.update(
            "UPDATE voice_room_member SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE room_id=9301 AND user_id=3"
        );
        core.snapshot(1, ROOM);
        check(generation(3) == 2, "Explicit expiry fixture did not retire identity");
        closure.begin(1, ROOM);
        long closingJobs = jobs();
        closure.begin(1, ROOM);
        check(jobs() == closingJobs, "Repeated close duplicated retirements");
        check(
            "INACTIVE".equals(
                jdbc.queryForObject(
                    "SELECT binding_state FROM voice_media_binding WHERE room_id=9301 AND user_id=2",
                    String.class
                )
            ),
            "CLOSING left active binding"
        );
        expect(() -> join(4), cn.kokonexus.common.api.ResourceNotFoundException.class);
        System.out.println(
            "PASS MEDIA_SQL_3 down/leave/rejoin monotonic identity/old session denial/explicit expiry/closing idempotence"
        );

        var claimed = new ArrayList<cn.kokonexus.voice.domain.VoiceMediaPlan.Retirement>();
        for (String method : List.of("duePending", "dueExpired")) {
            String query = sql
                .getSqlSessionFactory()
                .getConfiguration()
                .getMappedStatement("cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper." + method)
                .getBoundSql(Map.of("limit", 4))
                .getSql();
            var plan = jdbc.queryForList("EXPLAIN " + query, 4).getFirst();
            check(
                plan
                    .get("key")
                    .toString()
                    .equals(method.equals("duePending") ? "idx_voice_retire_due" : "idx_voice_retire_expired") &&
                    !String.valueOf(plan.get("Extra")).contains("filesort"),
                "Actual production claim SQL does not use ordered index"
            );
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var futures = new ArrayList<Future<List<cn.kokonexus.voice.domain.VoiceMediaPlan.Retirement>>>();
            for (int i = 0; i < 2; i++) futures.add(
                pool.submit(() -> {
                    barrier.await();
                    return retirement.claim(UUID.randomUUID().toString());
                })
            );
            for (var future : futures) claimed.addAll(future.get(10, TimeUnit.SECONDS));
        }
        check(
            claimed.size() == closingJobs &&
                claimed
                    .stream()
                    .map(j -> j.getId())
                    .distinct()
                    .count() == claimed.size(),
            "Concurrent claim duplicated or lost jobs"
        );
        check(claimed.stream().allMatch(j -> j.getAttempts() == 1), "Claim must consume budget before I/O");
        var job = claimed.getFirst();
        check(!retirement.confirmed(job.getId(), UUID.randomUUID().toString()), "Wrong lease confirmed");
        jdbc.update(
            "UPDATE voice_media_retirement SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",
            job.getId()
        );
        check(!retirement.confirmed(job.getId(), job.getLeaseToken()), "Expired owner confirmed");
        var renewed = retirement.claim(UUID.randomUUID().toString()).getFirst();
        check(
            renewed.getId().equals(job.getId()) &&
                renewed.getAttempts() == 2 &&
                renewed.getMediaIdentity().equals(job.getMediaIdentity()),
            "Restart retry changed immutable target or budget"
        );
        check(!retirement.failed(job), "Old failure overwrote new lease");
        check(retirement.confirmed(renewed.getId(), renewed.getLeaseToken()), "Current lease confirmation failed");
        System.out.println(
            "PASS MEDIA_SQL_4 two-connection SKIP LOCKED/unique claim/consumed budget/expired CAS/old-worker rejection"
        );

        for (var other : claimed)
            if (!other.getId().equals(job.getId())) check(
                retirement.confirmed(other.getId(), other.getLeaseToken()),
                "Other confirmation failed"
            );
        String exhausted = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO voice_media_retirement(id,room_id,user_id,generation,provider_room_name,media_identity,attempts,next_attempt_at,created_at) VALUES(?,9301,99,999,'koko-voice-9301',?,9,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
            exhausted,
            UUID.randomUUID().toString()
        );
        var last = retirement.claim(UUID.randomUUID().toString()).getFirst();
        check(last.getAttempts() == 10, "Tenth claim budget not consumed");
        jdbc.update(
            "UPDATE voice_media_retirement SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",
            last.getId()
        );
        retirement.exhaustExpired();
        check(
            retirement.claim(UUID.randomUUID().toString()).isEmpty(),
            "Exhausted crashed worker got eleventh I/O budget"
        );
        check(
            "DEAD".equals(
                jdbc.queryForObject(
                    "SELECT job_state FROM voice_media_retirement WHERE id=?",
                    String.class,
                    last.getId()
                )
            ),
            "Expired tenth claim not DEAD"
        );
        check(!retirement.confirmed(last.getId(), last.getLeaseToken()), "Dead job confirmed by stale worker");
        System.out.println("PASS MEDIA_SQL_5 actual crash-expired tenth budget exhausted to DEAD, no eleventh claim");

        var room = new VoiceRoom();
        room.setId(ROOM);
        room.setControlMode("CONTROLLED");
        room.setStatus("CLOSING");
        expect(() -> recorder.reconcile(room), IllegalStateException.class);
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_media_binding WHERE room_id=9301 AND binding_state='ACTIVE'",
                Integer.class
            ) == 0,
            "Closing active count changed"
        );
        closure.finish(1, ROOM);
        closure.finish(1, ROOM);
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,max_participants,control_mode,provider_room_name) VALUES(9302,9,'合成旧房主','synthetic-legacy-media','合成旧房间','OPEN',10,'LEGACY','koko-voice-9302')"
        );
        closure.begin(9, 9302);
        closure.finish(9, 9302);
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM voice_media_binding WHERE room_id=9302", Integer.class) == 0,
            "Legacy close created media binding"
        );
        System.out.println(
            "PASS MEDIA_SQL_6 transaction-required/closed receipt/legacy unaffected; no RTC media-ready claim"
        );

        // 真实SQL确认故障：媒体调用为明确成功桩，不应被SQL确认异常改成供应商失败。
        String confirmId = UUID.randomUUID().toString(),
            confirmIdentity = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO voice_media_retirement(id,room_id,user_id,generation,provider_room_name,media_identity,next_attempt_at,created_at) VALUES(?,9301,98,998,'koko-voice-9301',?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
            confirmId,
            confirmIdentity
        );
        var gateway = org.mockito.Mockito.mock(cn.kokonexus.voice.infrastructure.media.VoiceMediaGateway.class);
        var deletes = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            check(
                !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "SFU call held SQL transaction"
            );
            check(
                "PROCESSING".equals(
                    jdbc.queryForObject(
                        "SELECT job_state FROM voice_media_retirement WHERE id=?",
                        String.class,
                        confirmId
                    )
                ),
                "Claim not committed before I/O"
            );
            check(confirmIdentity.equals(invocation.getArgument(1)), "Retry changed immutable target");
            deletes.incrementAndGet();
            return null;
        })
            .when(gateway)
            .removeParticipant(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var worker = new cn.kokonexus.voice.infrastructure.media.VoiceMediaRetirementWorker(retirement, gateway);
        jdbc.execute(
            "CREATE TRIGGER synthetic_fail_done BEFORE UPDATE ON voice_media_retirement FOR EACH ROW BEGIN IF NEW.job_state='DONE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic confirmation rollback'; END IF; END"
        );
        try {
            worker.tick();
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_fail_done");
        }
        check(
            deletes.get() == 1 &&
                "PROCESSING".equals(
                    jdbc.queryForObject(
                        "SELECT job_state FROM voice_media_retirement WHERE id=?",
                        String.class,
                        confirmId
                    )
                ),
            "SQL failure misreported as SFU retry or committed DONE"
        );
        jdbc.update(
            "UPDATE voice_media_retirement SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",
            confirmId
        );
        worker.tick();
        check(
            deletes.get() == 2 &&
                "DONE".equals(
                    jdbc.queryForObject(
                        "SELECT job_state FROM voice_media_retirement WHERE id=?",
                        String.class,
                        confirmId
                    )
                ),
            "SQL recovery did not repeat old identity idempotently"
        );
        var view = core.mediaPlan(1, ROOM);
        check(
            view.tracked() && !view.mediaReady() && view.deadRetirements() == 1,
            "Closed owner diagnostic changed media-ready or dead fact"
        );
        expect(() -> core.mediaPlan(2, ROOM), cn.kokonexus.common.api.ForbiddenOperationException.class);
        System.out.println(
            "PASS MEDIA_SQL_7 real committed claim before nontransactional media stub/SQL confirmation rollback/restart retry/closed-owner diagnostic"
        );

        long roleRoom = 9303;
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,max_participants,control_mode,provider_room_name) VALUES(9303,6,'合成角色房主','synthetic-media-roles','合成角色边界','OPEN',10,'CONTROLLED','koko-voice-9303')"
        );
        var owner = core.join(6, roleRoom, UUID.randomUUID().toString(), "0", "合成房主6");
        var listener = core.join(7, roleRoom, UUID.randomUUID().toString(), roomVersion(roleRoom), "合成听众7");
        core.command(
            7,
            roleRoom,
            UUID.randomUUID().toString(),
            listener.sessionId(),
            roomVersion(roleRoom),
            CommandType.APPLY,
            1,
            null,
            null,
            null
        );
        check(
            jdbc.queryForObject(
                "SELECT generation FROM voice_media_binding WHERE room_id=9303 AND user_id=7",
                Long.class
            ) == 1,
            "Pending application changed media authorization"
        );
        String request = core.snapshot(6, roleRoom).requests().getFirst().id();
        core.command(
            6,
            roleRoom,
            UUID.randomUUID().toString(),
            owner.sessionId(),
            roomVersion(roleRoom),
            CommandType.ACCEPT,
            null,
            null,
            request,
            null
        );
        core.command(
            6,
            roleRoom,
            UUID.randomUUID().toString(),
            owner.sessionId(),
            roomVersion(roleRoom),
            CommandType.ADMIN,
            null,
            7L,
            null,
            true
        );
        check(
            jdbc.queryForObject(
                "SELECT generation FROM voice_media_binding WHERE room_id=9303 AND user_id=7",
                Long.class
            ) == 2 &&
                !jdbc.queryForObject(
                    "SELECT publish_desired FROM voice_media_binding WHERE room_id=9303 AND user_id=7",
                    Boolean.class
                ),
            "Admin grant incorrectly opened publishing"
        );
        core.command(
            7,
            roleRoom,
            UUID.randomUUID().toString(),
            listener.sessionId(),
            roomVersion(roleRoom),
            CommandType.MUTE,
            1,
            null,
            null,
            false
        );
        core.command(
            6,
            roleRoom,
            UUID.randomUUID().toString(),
            owner.sessionId(),
            roomVersion(roleRoom),
            CommandType.TRANSFER,
            null,
            7L,
            null,
            null
        );
        check(
            jdbc.queryForObject(
                "SELECT generation FROM voice_media_binding WHERE room_id=9303 AND user_id=7",
                Long.class
            ) == 3,
            "Transfer incorrectly changed stable seat grant"
        );
        expect(
            () ->
                core.command(
                    6,
                    roleRoom,
                    UUID.randomUUID().toString(),
                    owner.sessionId(),
                    roomVersion(roleRoom),
                    CommandType.KICK,
                    1,
                    null,
                    null,
                    null
                ),
            cn.kokonexus.common.api.ForbiddenOperationException.class
        );
        core.command(
            7,
            roleRoom,
            UUID.randomUUID().toString(),
            listener.sessionId(),
            roomVersion(roleRoom),
            CommandType.DOWN,
            1,
            null,
            null,
            null
        );
        core.command(
            7,
            roleRoom,
            UUID.randomUUID().toString(),
            listener.sessionId(),
            roomVersion(roleRoom),
            CommandType.INVITE,
            2,
            6L,
            null,
            null
        );
        String invitation = core.snapshot(7, roleRoom).requests().getFirst().id();
        core.command(
            6,
            roleRoom,
            UUID.randomUUID().toString(),
            owner.sessionId(),
            roomVersion(roleRoom),
            CommandType.ACCEPT,
            null,
            null,
            invitation,
            null
        );
        check(
            jdbc.queryForObject(
                "SELECT generation FROM voice_media_binding WHERE room_id=9303 AND user_id=6",
                Long.class
            ) == 2 &&
                !jdbc.queryForObject(
                    "SELECT publish_desired FROM voice_media_binding WHERE room_id=9303 AND user_id=6",
                    Boolean.class
                ),
            "Invitation acceptance bypassed muted default"
        );
        System.out.println(
            "PASS MEDIA_SQL_8 apply/accept/invite/consent/role grant/owner transfer do not confer implicit publisher permission"
        );
        System.out.println(
            "PASS VOICE_MEDIA_PLAN_MYSQL_ALL SQL/transaction only; no production/Gateway/LiveKit/RTC acceptance"
        );
    }

    private static <T> T proxy(T target, DataSourceTransactionManager manager) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
    }

    private static void join(long user) {
        sessions.put(
            user,
            core.join(user, ROOM, UUID.randomUUID().toString(), version(), "合成成员" + user).sessionId()
        );
    }

    private static String version() {
        return jdbc.queryForObject("SELECT interaction_version FROM voice_room WHERE id=9301", String.class);
    }

    private static String roomVersion(long room) {
        return jdbc.queryForObject("SELECT interaction_version FROM voice_room WHERE id=?", String.class, room);
    }

    private static long generation(long user) {
        return jdbc.queryForObject(
            "SELECT generation FROM voice_media_binding WHERE room_id=9301 AND user_id=?",
            Long.class,
            user
        );
    }

    private static String identity(long user) {
        return jdbc.queryForObject(
            "SELECT media_identity FROM voice_media_binding WHERE room_id=9301 AND user_id=?",
            String.class,
            user
        );
    }

    private static boolean publish(long user) {
        return jdbc.queryForObject(
            "SELECT publish_desired FROM voice_media_binding WHERE room_id=9301 AND user_id=?",
            Boolean.class,
            user
        );
    }

    private static long jobs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM voice_media_retirement WHERE room_id=9301", Long.class);
    }

    private static void command(long user, CommandType type, Integer seat, Long target, Boolean value) {
        core.command(
            user,
            ROOM,
            UUID.randomUUID().toString(),
            sessions.get(user),
            version(),
            type,
            seat,
            target,
            null,
            value
        );
    }

    private static void expect(Runnable action, Class<? extends Throwable> type) {
        try {
            action.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (RuntimeException failure) {
            if (!type.isInstance(failure)) throw failure;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
