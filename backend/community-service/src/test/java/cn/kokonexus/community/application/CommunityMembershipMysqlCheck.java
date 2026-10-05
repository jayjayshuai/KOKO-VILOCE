package cn.kokonexus.community.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.community.infrastructure.persistence.CommunityMapper;
import cn.kokonexus.community.infrastructure.persistence.CommunityMemberMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 实际 MP/XML/Spring 事务，仅允许明确命名的隔离库，不启动服务或访问业务生产表。 */
public final class CommunityMembershipMysqlCheck {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("COMMUNITY_CHECK_JDBC");
        if (url == null || !url.startsWith("jdbc:mysql://mysql:3306/koko_membership_check_20261002?")) {
            throw new IllegalArgumentException("必须使用指定隔离数据库");
        }
        var source = new DriverManagerDataSource(
            url,
            System.getenv("COMMUNITY_CHECK_USER"),
            System.getenv("COMMUNITY_CHECK_PASSWORD")
        );
        for (String migration : List.of(
            "V1__community.sql",
            "V2__community_lifecycle.sql",
            "V3__creator_post.sql",
            "V4__post_interactions.sql",
            "V5__post_favorites.sql",
            "V6__notification_outbox.sql",
            "V7__outbox_telemetry_index.sql",
            "V8__post_managed_cover.sql",
            "V9__community_membership.sql"
        )) {
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            }
        }
        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(
            new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml")
        );
        var sessions = new SqlSessionTemplate(factoryBean.getObject());
        var communities = sessions.getMapper(CommunityMapper.class);
        var members = sessions.getMapper(CommunityMemberMapper.class);
        var manager = new DataSourceTransactionManager(source);
        var service = proxy(new CommunityMembershipService(communities, members), manager);
        var lifecycle = proxy(new CommunityApplicationService(communities, members), manager);
        var jdbc = new JdbcTemplate(source);
        long id = lifecycle.create(42, "membership-check", "成员验收", "isolated", "TEST").getId();

        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 16; index++) {
                futures.add(executor.submit(() -> service.join(43, id, identity(43))));
            }
            for (var future : futures) future.get();
        }
        check(count(jdbc, id) == 2 && storedCount(jdbc, id) == 2, "重复入会导致重复人数");
        check(communities.selectById(id).getVersion() == 1, "重试消耗版本");
        check(service.status(43, id).role().equals("MEMBER"), "入会角色错误");
        rejects(ForbiddenOperationException.class, () -> service.remove(43, id, 42));
        rejects(IllegalStateException.class, () -> service.leave(42, id));
        rejects(IllegalStateException.class, () -> service.remove(42, id, 42));
        rejects(ResourceNotFoundException.class, () -> service.memberPage(44, id, null, 20));
        service.join(44, id, identity(44));
        check(service.memberPage(43, id, null, 1).size() == 2, "分页未保留下一批判断行");
        check(service.memberPage(43, id, 43L, 20).getFirst().getUserId() == 42, "独占成员游标错误");
        check(service.joined(44, null, 20).getFirst().getId() == id, "本人列表缺少关系");
        var beforePrivate = communities.selectById(id);
        lifecycle.update(42, id, beforePrivate.getVersion(), "私密验收", "isolated", "TEST", "PRIVATE");
        rejects(ResourceNotFoundException.class, () -> service.status(45, id));
        rejects(ResourceNotFoundException.class, () -> service.join(45, id, identity(45)));
        service.join(43, id, identity(43));
        check(service.status(43, id).role().equals("MEMBER"), "私密现成员重试失去原事实");
        service.leave(44, id);
        service.leave(44, id);
        check(count(jdbc, id) == 2 && storedCount(jdbc, id) == 2, "退出重试导致计数错误");
        check(service.joined(44, null, 20).isEmpty(), "退出后仍在本人列表");

        // 外层先建立旧 RR 快照，再并发移除；授权必须读已提交的当前成员事实。
        var tx = new TransactionTemplate(manager);
        tx.execute(status -> {
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM community_member WHERE community_id=? AND user_id=43",
                    Long.class,
                    id
                ) == 1,
                "旧快照未建立"
            );
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> service.remove(42, id, 43)).get();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            rejects(ResourceNotFoundException.class, () -> service.status(43, id));
            rejects(ResourceNotFoundException.class, () -> service.memberPage(43, id, null, 20));
            status.setRollbackOnly();
            return null;
        });

        // 人数更新 SQL 失败必须回滚已插入关系；只在隔离库建立故障 CHECK。
        long rollback = lifecycle.create(42, "membership-rollback", "回滚验收", "isolated", "TEST").getId();
        jdbc.execute(
            "ALTER TABLE community ADD CONSTRAINT check_membership_fault CHECK (id <> " +
                rollback +
                " OR member_count < 2)"
        );
        try {
            service.join(43, rollback, identity(43));
            throw new AssertionError("预期 SQL 拒绝");
        } catch (org.springframework.dao.DataAccessException expected) {}
        check(count(jdbc, rollback) == 1 && storedCount(jdbc, rollback) == 1, "成员/人数未完整回滚");
        check(communities.selectById(rollback).getVersion() == 0, "失败仍消耗版本");
        jdbc.execute("ALTER TABLE community DROP CHECK check_membership_fault");

        var seed = new ArrayList<Object[]>();
        for (long user = 100; user < 1098; user++) seed.add(new Object[] { rollback, user });
        jdbc.batchUpdate("INSERT INTO community_member(community_id,user_id,role) VALUES(?,?,'MEMBER')", seed);
        jdbc.update("UPDATE community SET member_count=999 WHERE id=?", rollback);
        int accepted = 0;
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (long user = 2000; user < 2008; user++) {
                long candidate = user;
                futures.add(
                    executor.submit(() -> {
                        try {
                            service.join(candidate, rollback, identity(candidate));
                            return true;
                        } catch (IllegalStateException capacity) {
                            check(capacity.getMessage().contains("1000"), "非配额拒绝");
                            return false;
                        }
                    })
                );
            }
            for (var future : futures) if (future.get()) accepted++;
        }
        check(
            accepted == 1 && count(jdbc, rollback) == 1000 && storedCount(jdbc, rollback) == 1000,
            "并发入会超过配额或账实不符"
        );
        var current = communities.selectById(rollback);
        lifecycle.archive(42, rollback, current.getVersion());
        rejects(ResourceNotFoundException.class, () -> service.join(3000, rollback, identity(3000)));
        rejects(ResourceNotFoundException.class, () -> service.memberPage(42, rollback, null, 20));
        long archivedVersion = communities.selectById(rollback).getVersion();
        service.leave(100, rollback);
        check(
            count(jdbc, rollback) == 1000 && communities.selectById(rollback).getVersion() == archivedVersion,
            "归档后修改关系"
        );
        check(
            service
                .joined(42, null, 20)
                .stream()
                .noneMatch(value -> value.getId() == rollback),
            "归档仍出现在本人列表"
        );
        long raceId = lifecycle.create(42, "membership-archive-race", "归档竞争", "isolated", "TEST").getId();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var mapperProxy = new ProxyFactory(communities);
        mapperProxy.addAdvice(
            (org.aopalliance.intercept.MethodInterceptor) invocation -> {
                if (invocation.getMethod().getName().equals("lock")) entered.countDown();
                return invocation.proceed();
            }
        );
        var contender = proxy(
            new CommunityMembershipService((CommunityMapper) mapperProxy.getProxy(), members),
            manager
        );
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            tx.execute(status -> {
                communities.lock(raceId);
                pending.set(
                    executor.submit(() -> {
                        try {
                            contender.join(43, raceId, identity(43));
                            return false;
                        } catch (ResourceNotFoundException archived) {
                            return true;
                        }
                    })
                );
                try {
                    check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "竞争未进入锁读取");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                check(!pending.get().isDone(), "持有社区锁时入会已完成");
                lifecycle.archive(42, raceId, 0);
                return null;
            });
            check(pending.get().get(10, java.util.concurrent.TimeUnit.SECONDS), "等待锁的入会绕过归档");
        }
        check(count(jdbc, raceId) == 1 && storedCount(jdbc, raceId) == 1, "归档竞争改变人数");
        System.out.println(
            "PASS MySQL membership: migrations, 16 retry race, literal snapshots, private/owner/isolation/page, old RR revocation, SQL rollback, 8 capacity race, archived boundaries/accounting"
        );
    }

    private static long count(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_member WHERE community_id=?", Long.class, id);
    }

    private static long storedCount(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT member_count FROM community WHERE id=?", Long.class, id);
    }

    private static ChatIdentity identity(long id) {
        return new ChatIdentity(Long.toString(id), "member" + id, "Member " + id);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable call) {
        try {
            call.run();
            throw new AssertionError("预期拒绝 " + type.getSimpleName());
        } catch (RuntimeException exception) {
            if (!type.isInstance(exception)) throw exception;
        }
    }
}
