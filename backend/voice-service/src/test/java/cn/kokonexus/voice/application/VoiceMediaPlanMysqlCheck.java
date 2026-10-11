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

/** 固定独立MySQL、生产XML/事务与隔离JWT签名；不访问项目库或物理SFU，不输出凭据。 */
public class VoiceMediaPlanMysqlCheck {

    /** 合成高熵网站会话摘要，不是生产Cookie。 */ private static final String SCOPE =
        cn.kokonexus.api.voice.WebsiteSessionScope.fromToken("synthetic-website-token");

    /** 固定合成房间。 */ private static final long ROOM = 9301;
    /** 真实当前核心事务代理。 */ private static VoiceInteractionService core;
    /** 合成成员服务器会话，不打印值。 */ private static final Map<Long, String> sessions = new HashMap<>();
    /** 仅固定实验室Schema的SQL。 */ private static JdbcTemplate jdbc;

    public static void main(String[] args) throws Exception {
        String url = System.getenv("VOICE_MEDIA_JDBC"),
            user = System.getenv("VOICE_MEDIA_USER");
        check(
            url != null &&
                url.matches("jdbc:mysql://127\\.0\\.0\\.1:33079/koko_voice_media_check_202610(?:06|10|11)\\?.+"),
            "Fixed local isolated schema required"
        );
        check(
            List.of(
                "koko_voice_media_check_20261006",
                "koko_voice_media_check_20261010",
                "koko_voice_media_check_20261011"
            ).contains(user) && url.startsWith("jdbc:mysql://127.0.0.1:33079/" + user + "?"),
            "Exact schema-only lab account required"
        );
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
        check(flyway.migrate().migrationsExecuted == 6, "Fresh V1-V6 required");
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
            "PASS MEDIA_SQL_1 actual V1-V5/FKs/scope account/join/opaque seat generation/same transaction"
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
        check(media.expiredMemberRooms(0, 4).equals(List.of(ROOM)), "Background expired-room discovery missing");
        String scanSql = sql
            .getSqlSessionFactory()
            .getConfiguration()
            .getMappedStatement("cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper.expiredMemberRooms")
            .getBoundSql(Map.of("after", 0L, "limit", 4))
            .getSql();
        check(
            jdbc
                .queryForList("EXPLAIN " + scanSql, 0L, 4)
                .stream()
                .anyMatch(row -> "idx_voice_expired_members".equals(row.get("key"))),
            "Real expired lease index not selected"
        );
        long beforeExpiryJobs = jobs();
        String beforeExpiryVersion = version();
        jdbc.execute(
            "CREATE TRIGGER synthetic_fail_reap_audit BEFORE INSERT ON voice_room_action FOR EACH ROW BEGIN IF NEW.command_type='EXPIRE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic reap rollback'; END IF; END"
        );
        try {
            new cn.kokonexus.voice.infrastructure.media.VoiceSessionReaper(
                media,
                core,
                true,
                true,
                true,
                4,
                5000
            ).tick();
            check(
                generation(3) == 1 && jobs() == beforeExpiryJobs && version().equals(beforeExpiryVersion),
                "Background audit failure did not roll back binding/version/job"
            );
            check(
                "ACTIVE".equals(
                    jdbc.queryForObject(
                        "SELECT member_state FROM voice_room_member WHERE room_id=9301 AND user_id=3",
                        String.class
                    )
                ),
                "Failed background expiry left partial member state"
            );
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_fail_reap_audit");
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) futures.add(
                pool.submit(() -> {
                    barrier.await();
                    new cn.kokonexus.voice.infrastructure.media.VoiceSessionReaper(
                        media,
                        core,
                        true,
                        true,
                        true,
                        4,
                        5000
                    ).tick();
                    return null;
                })
            );
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        check(
            generation(3) == 2 &&
                jobs() == beforeExpiryJobs + 1 &&
                Long.parseLong(version()) == Long.parseLong(beforeExpiryVersion) + 1,
            "Concurrent background expiry duplicated version/retirement"
        );
        check(media.expiredMemberRooms(0, 4).isEmpty(), "Completed expiry remains in scan");
        System.out.println(
            "PASS MEDIA_SQL_BACKGROUND_V5 indexed autonomous reaping/audit rollback/two-worker idempotence; no page snapshot used"
        );
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
        var admission = proxy(
            new VoiceMediaAdmissionState(
                sql.getMapper(VoiceRoomMapper.class),
                sql.getMapper(VoiceInteractionMapper.class),
                media,
                true
            ),
            manager
        );
        var verify = new cn.kokonexus.voice.infrastructure.media.LiveKitJoinTokenVerifier(
            "isolated-binding-admission-key",
            "synthetic-binding-admission-secret-key-private"
        );
        String boundIdentity = jdbc.queryForObject(
            "SELECT media_identity FROM voice_media_binding WHERE room_id=9303 AND user_id=6",
            String.class
        );
        jdbc.update("UPDATE voice_media_binding SET website_session_hash=? WHERE room_id=9303 AND user_id=6", SCOPE);
        var bound = verify.verify(boundJwt(boundIdentity, false), "6");
        check(bound != null && bound.binding(), "Actual SDK opaque token did not verify");
        check(
            !admission.allows(bound, 6, true, SCOPE) && admission.allows(bound, 6, false, SCOPE),
            "Entry barrier confused with existing binding retention"
        );
        for (
            var batch = retirement.claim(UUID.randomUUID().toString());
            !batch.isEmpty();
            batch = retirement.claim(UUID.randomUUID().toString())
        ) for (var done : batch)
            check(
                retirement.confirmed(done.getId(), done.getLeaseToken()),
                "Explicit lab retirement confirmation failed"
            );
        check(admission.allows(bound, 6, true, SCOPE), "Current bound audience admission denied");
        check(!admission.allows(bound, 7, true, SCOPE), "Opaque token accepted by other website user");
        core.command(
            6,
            roleRoom,
            UUID.randomUUID().toString(),
            owner.sessionId(),
            roomVersion(roleRoom),
            CommandType.MUTE,
            2,
            null,
            null,
            false
        );
        check(!admission.allows(bound, 6, false, SCOPE), "Old epoch retained after publish permission change");
        String freshIdentity = jdbc.queryForObject(
            "SELECT media_identity FROM voice_media_binding WHERE room_id=9303 AND user_id=6",
            String.class
        );
        jdbc.update("UPDATE voice_media_binding SET website_session_hash=? WHERE room_id=9303 AND user_id=6", SCOPE);
        var fresh = verify.verify(boundJwt(freshIdentity, true), "6");
        check(
            admission.allows(fresh, 6, false, SCOPE) && !admission.allows(fresh, 6, true, SCOPE),
            "Fresh identity ignored pending old retire barrier"
        );
        var mismatched = verify.verify(boundJwt(freshIdentity, false), "6");
        check(!admission.allows(mismatched, 6, false, SCOPE), "Publish claim disagreed with current binding");
        var credentials = proxy(
            new VoiceMediaCredentialState(
                sql.getMapper(VoiceRoomMapper.class),
                sql.getMapper(VoiceInteractionMapper.class),
                media,
                recorder
            ),
            manager
        );
        expect(
            () -> credentials.current(roleRoom, 6, owner.sessionId(), roomVersion(roleRoom), SCOPE),
            cn.kokonexus.common.api.ExternalDependencyUnavailableException.class
        );
        for (
            var batch = retirement.claim(UUID.randomUUID().toString());
            !batch.isEmpty();
            batch = retirement.claim(UUID.randomUUID().toString())
        ) for (var done : batch)
            check(retirement.confirmed(done.getId(), done.getLeaseToken()), "Lab retirement confirmation failed");
        var grant = credentials.current(roleRoom, 6, owner.sessionId(), roomVersion(roleRoom), SCOPE);
        check(
            grant.publish() && grant.seatNo() == 2 && freshIdentity.equals(grant.identity()),
            "SQL credential projection disagreed with seat/epoch"
        );
        expect(
            () -> credentials.current(roleRoom, 6, UUID.randomUUID().toString(), roomVersion(roleRoom), SCOPE),
            cn.kokonexus.common.api.ForbiddenOperationException.class
        );
        expect(() -> credentials.current(roleRoom, 6, owner.sessionId(), "0", SCOPE), IllegalStateException.class);
        var signer = new cn.kokonexus.voice.infrastructure.media.LiveKitVoiceMediaGateway(
            "http://127.0.0.1:1",
            "wss://app.example.invalid/api/media/livekit",
            "isolated-binding-admission-key",
            "synthetic-binding-admission-secret-key-private"
        );
        var signed = verify.verify(
            signer.issueBoundJoinToken(grant.roomName(), grant.identity(), grant.name(), grant.publish()),
            "6"
        );
        check(
            signed != null && signed.publish() && admission.allows(signed, 6, true, SCOPE),
            "Actual SQL grant + SDK signature not admitted"
        );
        check(!admission.allows(signed, 7, true, SCOPE), "Current signed grant used by different website user");
        System.out.println(
            "PASS MEDIA_SQL_10 current SQL/transaction grant + actual SDK JWT, pending retirement/session/version/cross-user refusal; no SFU assertion"
        );
        jdbc.update(
            "UPDATE voice_room_member SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE room_id=9303 AND user_id=6"
        );
        check(!admission.allows(fresh, 6, false, SCOPE), "Expired member retained binding");
        expect(
            () -> credentials.current(roleRoom, 6, owner.sessionId(), roomVersion(roleRoom), SCOPE),
            cn.kokonexus.common.api.ForbiddenOperationException.class
        );
        check(
            !admission.allows(
                new cn.kokonexus.voice.infrastructure.media.LiveKitJoinTokenVerifier.VerifiedJoin(
                    9303,
                    "koko-voice-9303"
                ),
                6,
                true,
                SCOPE
            ),
            "Legacy identity bypassed controlled binding"
        );
        System.out.println(
            "PASS MEDIA_SQL_9 actual SDK signed UUID/current binding/cross-user denial/changed epoch/claim mismatch/expiry/entry-versus-retain barrier"
        );
        websiteSessionChecks(sql, manager, media, recorder, retirement, admission, credentials, verify);
        crossRoomGapChecks(sql, manager, media, recorder);
        System.out.println(
            "PASS VOICE_MEDIA_PLAN_MYSQL_ALL SQL/transaction only; no production/Gateway/LiveKit/RTC acceptance"
        );
    }

    /** 真MySQL当前行锁/事务及SDK签名：网站切换、迟到注销、回滚与双worker，不以桩证明清退。 */
    private static void websiteSessionChecks(
        SqlSessionTemplate sql,
        DataSourceTransactionManager manager,
        VoiceMediaPlanMapper media,
        VoiceMediaPlanRecorder recorder,
        VoiceMediaRetirementState retirement,
        VoiceMediaAdmissionState admission,
        VoiceMediaCredentialState credentials,
        cn.kokonexus.voice.infrastructure.media.LiveKitJoinTokenVerifier verify
    ) throws Exception {
        long room = 9304;
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,max_participants,control_mode,provider_room_name) VALUES(9304,8,'合成房主8','synthetic-website-lab','合成网站媒体','OPEN',10,'CONTROLLED','koko-voice-9304')"
        );
        var joined = core.join(8, room, UUID.randomUUID().toString(), "0", "合成网站主体8");
        core.command(
            8,
            room,
            UUID.randomUUID().toString(),
            joined.sessionId(),
            roomVersion(room),
            CommandType.PULL,
            1,
            8L,
            null,
            null
        );
        core.command(
            8,
            room,
            UUID.randomUUID().toString(),
            joined.sessionId(),
            roomVersion(room),
            CommandType.MUTE,
            1,
            null,
            null,
            false
        );
        drainSyntheticRetirements(retirement);
        var first = credentials.current(room, 8, joined.sessionId(), roomVersion(room), SCOPE);
        check(first.ready(), "First website scope did not claim current binding");
        var old = verify.verify(boundJwt(first.identity(), true, room), "8");
        check(admission.allows(old, 8, true, SCOPE), "Current website proof denied");
        String nextScope = cn.kokonexus.api.voice.WebsiteSessionScope.fromToken("synthetic-next-website-token");
        check(!admission.allows(old, 8, true, nextScope), "Different website reused same user's JWT");
        check(!admission.allows(old, 8, true, null), "Unscoped consumer bypassed website binding");
        long generation = Long.parseLong(first.generation());
        jdbc.execute(
            "CREATE TRIGGER synthetic_fail_website_retire BEFORE INSERT ON voice_media_retirement FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic website rollback'"
        );
        try {
            expect(
                () -> credentials.current(room, 8, joined.sessionId(), roomVersion(room), nextScope),
                org.springframework.dao.DataAccessException.class
            );
            check(
                media.binding(room, 8).getGeneration() == generation &&
                    SCOPE.equals(media.binding(room, 8).getWebsiteSessionHash()),
                "Failed website rotation did not roll back"
            );
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_fail_website_retire");
        }
        var switched = credentials.current(room, 8, joined.sessionId(), roomVersion(room), nextScope);
        check(
            !switched.ready() && !first.identity().equals(switched.identity()),
            "Website switch did not fence old identity before signing"
        );
        check(!admission.allows(old, 8, false, SCOPE), "Old website proof retained after new scope");
        var service = new VoiceWebsiteSessionRetirement(media, core);
        check(
            service.retire(new cn.kokonexus.api.voice.MediaWebsiteSessionCommand("8", SCOPE)),
            "Old logout registration incomplete"
        );
        check(
            "ACTIVE".equals(sql.getMapper(VoiceInteractionMapper.class).member(room, 8).getMemberState()) &&
                media.binding(room, 8).getMediaIdentity().equals(switched.identity()),
            "Late old logout touched new member/binding"
        );
        drainSyntheticRetirements(retirement);
        var current = credentials.current(room, 8, joined.sessionId(), roomVersion(room), nextScope);
        check(
            current.ready() && current.identity().equals(switched.identity()),
            "Retry unnecessarily rotated same website again"
        );
        var fresh = verify.verify(boundJwt(current.identity(), true, room), "8");
        check(admission.allows(fresh, 8, true, nextScope), "New website current proof denied");
        check(
            jdbc
                .queryForList(
                    "EXPLAIN SELECT room_id FROM voice_media_binding FORCE INDEX(idx_voice_website_binding) WHERE user_id=8 AND website_session_hash=? AND binding_state='ACTIVE' ORDER BY room_id LIMIT 16",
                    nextScope
                )
                .stream()
                .anyMatch(row -> "idx_voice_website_binding".equals(row.get("key"))),
            "Website discovery index missing"
        );
        System.out.println(
            "PASS MEDIA_SQL_WEBSITE_12 V6/current scope/cross-website denial/rotation rollback/immutable old target/late logout cannot remove new identity/retry/index"
        );
        jdbc.execute(
            "CREATE TRIGGER synthetic_fail_website_audit BEFORE INSERT ON voice_room_action FOR EACH ROW BEGIN IF NEW.command_type='WEBSITE_LOGOUT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic website audit rollback'; END IF; END"
        );
        try {
            expect(
                () -> service.retire(new cn.kokonexus.api.voice.MediaWebsiteSessionCommand("8", nextScope)),
                org.springframework.dao.DataAccessException.class
            );
            check(
                "ACTIVE".equals(sql.getMapper(VoiceInteractionMapper.class).member(room, 8).getMemberState()) &&
                    "ON_MIC".equals(
                        sql.getMapper(VoiceInteractionMapper.class).seats(room).getFirst().getSeatState()
                    ) &&
                    admission.allows(fresh, 8, true, nextScope),
                "Website logout failure did not roll back member/seat/binding"
            );
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_fail_website_audit");
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> {
                start.await();
                return service.retire(new cn.kokonexus.api.voice.MediaWebsiteSessionCommand("8", nextScope));
            });
            var b = pool.submit(() -> {
                start.await();
                return service.retire(new cn.kokonexus.api.voice.MediaWebsiteSessionCommand("8", nextScope));
            });
            start.countDown();
            check(
                a.get(5, TimeUnit.SECONDS) && b.get(5, TimeUnit.SECONDS),
                "Concurrent website retirement did not complete"
            );
        }
        check(
            "LEFT".equals(sql.getMapper(VoiceInteractionMapper.class).member(room, 8).getMemberState()) &&
                "EMPTY".equals(sql.getMapper(VoiceInteractionMapper.class).seats(room).getFirst().getSeatState()),
            "Website logout left member/seat active"
        );
        check(!admission.allows(fresh, 8, false, nextScope), "Website logout retained revoked JWT");
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_media_retirement WHERE room_id=9304 AND media_identity=?",
                Integer.class,
                current.identity()
            ) == 1,
            "Duplicate website logout created duplicate old target"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM voice_room_action WHERE room_id=9304 AND command_type='WEBSITE_LOGOUT'",
                Integer.class
            ) == 1,
            "Website logout audit not idempotent"
        );
        System.out.println(
            "PASS MEDIA_SQL_WEBSITE_13 logout audit failure rolls back whole member/seat/binding/job transaction; two workers register once; no physical SFU assertion"
        );
    }

    /** 明确的SQL任务确认夹具，不调用物理SFU、不用于公网清退结论。 */
    private static void drainSyntheticRetirements(VoiceMediaRetirementState retirement) {
        for (
            var batch = retirement.claim(UUID.randomUUID().toString());
            !batch.isEmpty();
            batch = retirement.claim(UUID.randomUUID().toString())
        ) for (var job : batch)
            check(retirement.confirmed(job.getId(), job.getLeaseToken()), "Synthetic retirement confirmation failed");
    }

    /** 两真实事务均读完不存在的成员后同时插入，覆盖不同房间空范围间隙锁互等。 */
    private static void crossRoomGapChecks(
        SqlSessionTemplate sql,
        DataSourceTransactionManager manager,
        VoiceMediaPlanMapper media,
        VoiceMediaPlanRecorder recorder
    ) throws Exception {
        for (long room : new long[] { 9305, 9306 }) {
            jdbc.update(
                "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,max_participants,control_mode,provider_room_name) VALUES(?,10,'合成并发房主',?,'合成并发入房','OPEN',10,'CONTROLLED',?)",
                room,
                "synthetic-gap-" + room,
                "koko-voice-" + room
            );
            for (int seat = 1; seat <= 8; seat++) jdbc.update(
                "INSERT INTO voice_seat(room_id,seat_no) VALUES(?,?)",
                room,
                seat
            );
        }
        var bothRead = new CountDownLatch(2);
        var delegate = sql.getMapper(VoiceInteractionMapper.class);
        var synchronizedMapper = (VoiceInteractionMapper) java.lang.reflect.Proxy.newProxyInstance(
            VoiceInteractionMapper.class.getClassLoader(),
            new Class[] { VoiceInteractionMapper.class },
            (object, method, args) -> {
                if (method.getName().equals("saveMember")) {
                    bothRead.countDown();
                    check(bothRead.await(2, TimeUnit.SECONDS), "Other room blocked before empty-member barrier");
                }
                try {
                    return method.invoke(delegate, args);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        );
        var concurrent = proxy(new VoiceInteractionService(synchronizedMapper, true, recorder), manager);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrent.join(10, 9305, UUID.randomUUID().toString(), "0", "合成并发主体"));
            var second = pool.submit(() ->
                concurrent.join(10, 9306, UUID.randomUUID().toString(), "0", "合成并发主体")
            );
            check(
                first.get(5, TimeUnit.SECONDS).sessionId() != null &&
                    second.get(5, TimeUnit.SECONDS).sessionId() != null,
                "Cross-room insertion failed"
            );
        }
        check(
            "ACTIVE".equals(delegate.member(9305, 10).getMemberState()) &&
                "ACTIVE".equals(delegate.member(9306, 10).getMemberState()) &&
                media.binding(9305, 10) != null &&
                media.binding(9306, 10) != null,
            "Cross-room member/binding commits not confirmed"
        );
        System.out.println(
            "PASS MEDIA_SQL_GAP_14 actual two-transaction empty-member barrier; RC with per-room mutex commits both members/media plans without cross-room gap deadlock"
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

    private static String boundJwt(String identity, boolean publish) {
        return boundJwt(identity, publish, 9303);
    }

    private static String boundJwt(String identity, boolean publish, long roomId) {
        var token = new io.livekit.server.AccessToken(
            "isolated-binding-admission-key",
            "synthetic-binding-admission-secret-key-private"
        );
        token.setIdentity(identity);
        token.setTtl(60000);
        token.addGrants(
            new io.livekit.server.RoomJoin(true),
            new io.livekit.server.RoomName("koko-voice-" + roomId),
            new io.livekit.server.CanSubscribe(true),
            new io.livekit.server.CanPublish(publish),
            new io.livekit.server.CanPublishData(false),
            new io.livekit.server.CanUpdateOwnMetadata(false),
            new io.livekit.server.CanPublishSources(List.of("microphone"))
        );
        return token.toJwt();
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
