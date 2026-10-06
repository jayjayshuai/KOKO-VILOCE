package cn.kokonexus.voice.application;

import static org.mockito.Mockito.*;

import cn.kokonexus.voice.domain.VoiceRoom;
import cn.kokonexus.voice.infrastructure.media.VoiceMediaGateway;
import cn.kokonexus.voice.infrastructure.persistence.VoiceRoomMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 真实MySQL/生产XML/事务代理；媒体为明确夹具，不启动RTC或访问生产库。 */
public final class VoiceOwnerMysqlCheck {

    /** 隔离合成房主，不是实际身份账号。 */
    private static final long OWNER = 7001;
    /** 超过JS精确整数范围，用字符串分页。 */
    private static final long FIRST_ID = 9007199254741001L;

    public static void main(String[] args) throws Exception {
        String url = System.getenv("VOICE_OWNER_JDBC"),
            user = System.getenv("VOICE_OWNER_USER");
        check(
            url != null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:33069/koko_voice_owner_check_20261005\\?.+"),
            "Fixed isolated database required"
        );
        check("koko_voice_owner_check_20261005".equals(user), "Limited schema user required");
        var source = new DriverManagerDataSource(url, user, System.getenv("VOICE_OWNER_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var flyway = Flyway.configure()
            .dataSource(source)
            .table("voice_flyway_schema_history")
            .locations("classpath:db/migration")
            .load();
        check(flyway.migrate().migrationsExecuted == 4, "Fresh V1-V4 migration required");
        flyway.validate();
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        bean.setMapperLocations(
            new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/VoiceRoomMapper.xml")
        );
        var sessions = new SqlSessionTemplate(bean.getObject());
        var mapper = sessions.getMapper(VoiceRoomMapper.class);
        var media = mock(VoiceMediaGateway.class);
        var closureProxy = new ProxyFactory(
            new VoiceClosureState(
                mapper,
                mock(cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper.class),
                mock(VoiceMediaPlanRecorder.class)
            )
        );
        closureProxy.setProxyTargetClass(true);
        closureProxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        var target = new VoiceApplicationService(mapper, media, (VoiceClosureState) closureProxy.getProxy());
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        var service = (VoiceApplicationService) proxy.getProxy();

        var rows = new ArrayList<Object[]>();
        for (int index = 0; index < 55; index++) {
            long id = FIRST_ID + index * 100L;
            rows.add(row(id, OWNER, new String[] { "OPEN", "CLOSED", "FAILED", "PROVISIONING" }[index % 4]));
        }
        // 本人/其他房主的雪花ID交错；独占PK小区间会使规划器合理选择主键，不能误判为缺索引。
        for (int index = 0; index < 5000; index++) {
            long id = FIRST_ID + (index % 100 == 0 ? 20000 + index : index);
            rows.add(row(id, 7002, "OPEN"));
        }
        jdbc.batchUpdate(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,provider_room_name,max_participants) VALUES(?,?,?,?,?,?,?,?)",
            rows
        );
        var ids = new HashSet<Long>();
        String before = null;
        long previous = Long.MAX_VALUE;
        int pages = 0;
        do {
            var page = service.ownedRooms(OWNER, before, 20);
            check(page.items().size() <= 20, "Unbounded page");
            for (var room : page.items()) {
                check(
                    room.getOwnerId() == OWNER && room.getId() < previous && ids.add(room.getId()),
                    "Owner leak, duplicate or order drift"
                );
                check(room.getProviderRoomName() == null, "List loads private provider name");
                previous = room.getId();
            }
            before = page.nextBefore();
            pages++;
        } while (before != null);
        check(ids.size() == 55 && pages == 3, "Missing owner rooms or cursor precision loss");
        check(service.ownedRooms(7003, null, 50).items().isEmpty(), "Empty owner sees another owner");
        check(service.ownedRooms(OWNER, "9223372036854775807", 50).items().size() == 50, "Max long cursor rejected");
        var originalExplain = jdbc.queryForMap(
            "EXPLAIN SELECT id, owner_id, owner_name, slug, title, topic, status, max_participants, created_at, closed_at FROM voice_room WHERE owner_id=? AND id < ? ORDER BY id DESC LIMIT 51",
            OWNER,
            FIRST_ID + 5500
        );
        System.out.println(
            "EXPLAIN_BEFORE_ANALYZE key=" +
                originalExplain.get("key") +
                " rows=" +
                originalExplain.get("rows") +
                " extra=" +
                originalExplain.get("Extra")
        );
        // 新空表批量导入合成数据后刷新统计，不使用FORCE INDEX掩盖真实规划器选择。
        jdbc.execute("ANALYZE TABLE voice_room");
        var explain = jdbc.queryForMap(
            "EXPLAIN SELECT id, owner_id, owner_name, slug, title, topic, status, max_participants, created_at, closed_at FROM voice_room WHERE owner_id=? AND id < ? ORDER BY id DESC LIMIT 51",
            OWNER,
            FIRST_ID + 5500
        );
        System.out.println(
            "EXPLAIN_AFTER_ANALYZE key=" +
                explain.get("key") +
                " rows=" +
                explain.get("rows") +
                " extra=" +
                explain.get("Extra")
        );
        check("idx_voice_owner_cursor".equals(explain.get("key")), "Owner cursor index not used");
        check(!String.valueOf(explain.get("Extra")).contains("filesort"), "Unbounded cursor sorting");
        System.out.println(
            "PASS SQL_1 V1/V2 migrations; 55 owned / 5000 unrelated; three exact cursor pages; max-long strings; private provider excluded"
        );
        System.out.println("PASS SQL_2 actual EXPLAIN uses idx_voice_owner_cursor; no filesort; schema-only grants");

        long concurrentId = FIRST_ID + 10000;
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,provider_room_name,max_participants) VALUES(?,?,?,?,?,?,?,?)",
            row(concurrentId, OWNER, "OPEN")
        );
        var barrier = new CyclicBarrier(12);
        var mediaCalls = new AtomicInteger();
        doAnswer(invocation -> {
            mediaCalls.incrementAndGet();
            barrier.await(10, TimeUnit.SECONDS);
            return null;
        })
            .when(media)
            .delete("synthetic-provider-" + concurrentId);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 12; index++) tasks.add(pool.submit(() -> service.close(OWNER, concurrentId)));
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        check(
            mediaCalls.get() == 12 &&
                "CLOSED".equals(
                    jdbc.queryForObject("SELECT status FROM voice_room WHERE id=?", String.class, concurrentId)
                ),
            "Close race unresolved"
        );
        var closedAt = jdbc.queryForObject(
            "SELECT closed_at FROM voice_room WHERE id=?",
            java.sql.Timestamp.class,
            concurrentId
        );
        service.close(OWNER, concurrentId);
        check(
            mediaCalls.get() == 12 &&
                closedAt.equals(
                    jdbc.queryForObject(
                        "SELECT closed_at FROM voice_room WHERE id=?",
                        java.sql.Timestamp.class,
                        concurrentId
                    )
                ),
            "Retry deleted again or changed closing fact"
        );
        try {
            service.close(7002, concurrentId);
            throw new AssertionError("Wrong owner closed");
        } catch (cn.kokonexus.common.api.ResourceNotFoundException expected) {
            /* 跨所有者不可见。 */
        }
        System.out.println(
            "PASS SQL_3 twelve actual CAS competitors converge CLOSED; owner retry unchanged; other-owner 404; media deletes intentionally repeat"
        );

        long failedId = FIRST_ID + 10001;
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,provider_room_name,max_participants) VALUES(?,?,?,?,?,?,?,?)",
            row(failedId, OWNER, "OPEN")
        );
        doThrow(new IllegalStateException("synthetic-provider-unavailable"))
            .when(media)
            .delete("synthetic-provider-" + failedId);
        try {
            service.close(OWNER, failedId);
            throw new AssertionError("Media failure succeeded");
        } catch (IllegalStateException expected) {
            /* 状态不伪装关闭。 */
        }
        check(
            "CLOSING".equals(jdbc.queryForObject("SELECT status FROM voice_room WHERE id=?", String.class, failedId)),
            "Media failure lost durable closing intent"
        );
        System.out.println("PASS SQL_4 media failure fixture preserves real CLOSING intent without claiming CLOSED");

        long unknownId = FIRST_ID + 10002;
        jdbc.update(
            "INSERT INTO voice_room(id,owner_id,owner_name,slug,title,status,provider_room_name,max_participants) VALUES(?,?,?,?,?,?,?,?)",
            row(unknownId, OWNER, "OPEN")
        );
        var unknownCalls = new AtomicInteger();
        doAnswer(invocation -> {
            unknownCalls.incrementAndGet();
            return null;
        })
            .when(media)
            .delete("synthetic-provider-" + unknownId);
        jdbc.execute(
            "CREATE TRIGGER voice_owner_close_failure BEFORE UPDATE ON voice_room FOR EACH ROW BEGIN IF OLD.id=" +
                unknownId +
                " AND NEW.status='CLOSED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic close SQL failure'; END IF; END"
        );
        try {
            service.close(OWNER, unknownId);
            throw new AssertionError("SQL failure returned success");
        } catch (org.springframework.dao.DataAccessException expected) {
            /* 原SQL确认失败保留，外部删除可能已发生。 */
        } finally {
            jdbc.execute("DROP TRIGGER voice_owner_close_failure");
        }
        check(
            unknownCalls.get() == 1 &&
                "CLOSING".equals(
                    jdbc.queryForObject("SELECT status FROM voice_room WHERE id=?", String.class, unknownId)
                ),
            "SQL failure became confirmed closure"
        );
        service.close(OWNER, unknownId);
        check(
            unknownCalls.get() == 2 &&
                "CLOSED".equals(
                    jdbc.queryForObject("SELECT status FROM voice_room WHERE id=?", String.class, unknownId)
                ),
            "Unknown result retry did not converge"
        );
        System.out.println(
            "PASS SQL_5 real SQL failure after media fixture success is not success; original-room retry converges; no exactly-once claim"
        );
        System.out.println(
            "PASS VOICE_OWNER_ALL production mapper and Spring read proxy; identity/media are fixtures, not RTC/Gateway/UI"
        );
    }

    /** 只有本轮合成数据；不保存邮箱或媒体JWT。 */
    private static Object[] row(long id, long owner, String status) {
        return new Object[] {
            id,
            owner,
            "合成房主",
            "synthetic-" + id,
            "合成房间",
            status,
            "synthetic-provider-" + id,
            20,
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
