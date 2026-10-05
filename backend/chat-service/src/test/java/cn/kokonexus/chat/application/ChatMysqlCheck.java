package cn.kokonexus.chat.application;

import cn.kokonexus.api.identity.ChatIdentity;
import cn.kokonexus.chat.persistence.ArchiveMapper;
import cn.kokonexus.chat.persistence.BlockMapper;
import cn.kokonexus.chat.persistence.ChatSyncMapper;
import cn.kokonexus.chat.persistence.ConversationMapper;
import cn.kokonexus.chat.persistence.MemberMapper;
import cn.kokonexus.chat.persistence.MessageMapper;
import cn.kokonexus.chat.persistence.ReportMapper;
import cn.kokonexus.chat.persistence.ReviewMapper;
import cn.kokonexus.chat.persistence.SafetyLockMapper;
import cn.kokonexus.common.api.ForbiddenOperationException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 真 MySQL 事务验收，只允许命名隔离 schema，不启动业务 RPC。 */
public final class ChatMysqlCheck {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("CHAT_CHECK_JDBC");
        if (
            url == null ||
            !url.matches(
                "jdbc:mysql://(?:mysql:3306/koko_chat_check_[0-9]+|127\\.0\\.0\\.1:33067/koko_chat_check_20261005)\\?.+"
            )
        ) throw new IllegalArgumentException("必须使用隔离数据库");
        var source = new DriverManagerDataSource(
            url,
            System.getenv("CHAT_CHECK_USER"),
            System.getenv("CHAT_CHECK_PASSWORD")
        );
        var jdbc = new JdbcTemplate(source);
        for (String migration : List.of(
            "V1__chat.sql",
            "V2__chat_safety.sql",
            "V3__chat_bookmarks.sql",
            "V4__chat_sync_revision.sql"
        )) {
            try (var stream = ChatMysqlCheck.class.getResourceAsStream("/db/migration/" + migration)) {
                for (String sql : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) jdbc.execute(sql);
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
        var factory = factoryBean.getObject();
        for (var mapper : List.of(MessageMapper.class, BlockMapper.class, ReportMapper.class, ReviewMapper.class))
            factory.getConfiguration().addMapper(mapper);
        var sessions = new SqlSessionTemplate(factory);
        var writerProxy = new ProxyFactory(new ChatSyncWriter(sessions.getMapper(ChatSyncMapper.class)));
        writerProxy.setProxyTargetClass(true);
        writerProxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        var syncWriter = (ChatSyncWriter) writerProxy.getProxy();
        var safetyTarget = new ChatSafetyService(
            sessions.getMapper(SafetyLockMapper.class),
            sessions.getMapper(BlockMapper.class),
            sessions.getMapper(ReportMapper.class),
            sessions.getMapper(ReviewMapper.class),
            sessions.getMapper(ConversationMapper.class),
            sessions.getMapper(MemberMapper.class),
            sessions.getMapper(MessageMapper.class),
            syncWriter
        );
        var safetyProxy = new ProxyFactory(safetyTarget);
        safetyProxy.setProxyTargetClass(true);
        safetyProxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        ChatSafetyService safety = (ChatSafetyService) safetyProxy.getProxy();
        var target = new ChatService(
            sessions.getMapper(ConversationMapper.class),
            sessions.getMapper(MemberMapper.class),
            sessions.getMapper(MessageMapper.class),
            safety,
            syncWriter
        );
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        ChatService service = (ChatService) proxy.getProxy();
        var archiveTarget = new ChatArchiveService(
            sessions.getMapper(ConversationMapper.class),
            sessions.getMapper(MemberMapper.class),
            sessions.getMapper(MessageMapper.class),
            sessions.getMapper(ArchiveMapper.class)
        );
        var archiveProxy = new ProxyFactory(archiveTarget);
        archiveProxy.setProxyTargetClass(true);
        archiveProxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        ChatArchiveService archive = (ChatArchiveService) archiveProxy.getProxy();
        var owner = identity(42);
        var other = identity(43);
        var third = identity(44);
        var direct = service.create(owner, List.of(other), "direct", false);
        check(
            direct.getId().equals(service.create(other, List.of(owner), "again", false).getId()),
            "Private pair not unique"
        );
        String id = service.create(owner, List.of(other), "group", true).getId();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Long>> writes = new ArrayList<>();
            for (int index = 0; index < 24; index++) writes.add(
                executor.submit(() -> service.send(42, id, UUID.randomUUID().toString(), "concurrent").getSeq())
            );
            var seqs = new java.util.HashSet<Long>();
            for (var write : writes) seqs.add(write.get());
            check(seqs.size() == 24 && seqs.contains(1L) && seqs.contains(24L), "Sequence race");
        }
        String retry = UUID.randomUUID().toString();
        var original = service.send(42, id, retry, "retry");
        check(original.getId().equals(service.send(42, id, retry, "retry").getId()), "Retry duplicated message");
        rejects(IllegalStateException.class, () -> service.send(42, id, retry, "changed"));
        check(
            jdbc.queryForObject("SELECT last_seq FROM chat_conversation WHERE id=?", Long.class, id) == 25,
            "Conflict changed sequence"
        );
        rejects(ResourceNotFoundException.class, () -> service.history(44, id, null, null, 100));
        rejects(ForbiddenOperationException.class, () -> service.add(43, id, third));
        service.add(42, id, third);
        check(service.history(44, id, null, null, 100).isEmpty(), "New member reads old history");
        var newest = service.send(43, id, UUID.randomUUID().toString(), "after join");
        check(service.history(44, id, null, null, 100).size() == 1, "New message missing");
        service.read(44, id, newest.getSeq());
        service.read(44, id, 0);
        check(
            jdbc.queryForObject(
                "SELECT read_seq FROM chat_member WHERE conversation_id=? AND user_id=44",
                Long.class,
                id
            ) == 26,
            "Read regressed"
        );
        rejects(IllegalArgumentException.class, () -> service.read(44, id, 999));
        service.remove(42, id, 44);
        rejects(ResourceNotFoundException.class, () -> service.send(44, id, UUID.randomUUID().toString(), "forbidden"));
        rejects(ResourceNotFoundException.class, () -> service.history(44, id, null, null, 100));
        service.add(42, id, third);
        check(service.history(44, id, null, null, 100).isEmpty(), "Rejoin reveals old history");
        rejects(IllegalStateException.class, () -> service.remove(42, id, 42));
        service.rename(42, id, "renamed");
        service.close(42, id);
        rejects(ResourceNotFoundException.class, () -> service.history(42, id, null, null, 100));
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?", Long.class, id) == 26,
            "Audit messages lost"
        );
        safetyChecks(service, safety, jdbc);
        quotaAndReviewRaceChecks(service, safety, jdbc);
        blockQuotaRaceCheck(safety, jdbc);
        staleSnapshotBlockCheck(service, safety, safetyTarget, sessions, source, jdbc);
        archiveChecks(service, archive, jdbc);
        System.out.println(
            "PASS MySQL chat: 24 concurrent sends, contiguous sequence, retry/conflict rollback, membership boundaries, monotonic read, remove/rejoin, rename/disband"
        );
    }

    private static void safetyChecks(ChatService service, ChatSafetyService safety, JdbcTemplate jdbc) {
        var owner = identity(42);
        var peer = identity(43);
        var outsider = identity(44);
        long pairCount = jdbc.queryForObject("SELECT COUNT(*) FROM chat_contact_lock", Long.class);
        safety.unblock(42, 987654);
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_contact_lock", Long.class) == pairCount,
            "Unblocking a nonexistent relationship allocated arbitrary contact rows"
        );
        String direct = service.create(owner, List.of(peer), "safety", false).getId();
        String retry = UUID.randomUUID().toString();
        var message = service.send(42, direct, retry, "real evidence");
        var block = safety.block(43, owner);
        check(safety.block(43, owner).getId().equals(block.getId()), "Block retry duplicated");
        check(safety.blocked(42, null, 100).isEmpty(), "Target sees other person's block list");
        rejects(ForbiddenOperationException.class, () ->
            service.send(42, direct, UUID.randomUUID().toString(), "blocked")
        );
        check(
            service.send(42, direct, retry, "real evidence").getId().equals(message.getId()),
            "Committed retry rejected after block"
        );
        rejects(ForbiddenOperationException.class, () -> service.create(owner, List.of(peer), "blocked", true));
        var report = safety.report(43, direct, message.getId(), "HARASSMENT", "check evidence");
        check(report.getEvidenceBody().equals(message.getBody()), "Evidence differs from stored message");
        check(
            safety.report(43, direct, message.getId(), "HARASSMENT", "check evidence").getId().equals(report.getId()),
            "Report retry duplicated"
        );
        rejects(IllegalStateException.class, () -> safety.report(43, direct, message.getId(), "SPAM", "changed"));
        rejects(ResourceNotFoundException.class, () -> safety.report(44, direct, message.getId(), "SPAM", "outsider"));
        rejects(IllegalArgumentException.class, () -> safety.report(42, direct, message.getId(), "SPAM", "self"));
        rejects(ResourceNotFoundException.class, () -> safety.mine(42, report.getId()));
        rejects(ForbiddenOperationException.class, () ->
            safety.review(43, report.getId(), 0, "RESOLVED", "self review")
        );
        rejects(ForbiddenOperationException.class, () ->
            safety.review(42, report.getId(), 0, "RESOLVED", "involved review")
        );
        rejects(IllegalStateException.class, () -> safety.review(45, report.getId(), 9, "RESOLVED", "stale"));
        check(safety.mine(43, report.getId()).getStatus().equals("PENDING"), "Stale review changed report");
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_report_review", Long.class) == 0,
            "Stale review created audit"
        );
        var reviewed = safety.review(45, report.getId(), 0, "RESOLVED", "handled");
        check(reviewed.getVersion() == 1 && reviewed.getStatus().equals("RESOLVED"), "Review not committed");
        safety.review(45, report.getId(), 0, "RESOLVED", "handled");
        rejects(IllegalStateException.class, () -> safety.review(46, report.getId(), 1, "REJECTED", "overwrite"));
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_report_review", Long.class) == 1,
            "Review audit duplicated"
        );
        safety.block(42, peer);
        safety.unblock(43, 42);
        safety.unblock(43, 42);
        rejects(ForbiddenOperationException.class, () ->
            service.send(43, direct, UUID.randomUUID().toString(), "other direction")
        );
        safety.unblock(42, 43);
        var group = service.create(owner, List.of(outsider), "invites", true);
        safety.block(43, owner);
        rejects(ForbiddenOperationException.class, () -> service.add(42, group.getId(), peer));
        service.send(42, group.getId(), UUID.randomUUID().toString(), "existing group allowed");
        safety.unblock(43, 42);
        service.add(42, group.getId(), peer);
        rejects(ResourceNotFoundException.class, () ->
            safety.report(
                43,
                group.getId(),
                service.history(42, group.getId(), null, null, 100).getFirst().getId(),
                "SPAM",
                "old history"
            )
        );
        check(
            service.send(43, direct, UUID.randomUUID().toString(), "unblocked").getSeq() == 2,
            "Unblock did not restore send"
        );
        String common = service.create(owner, List.of(peer), "shared group", true).getId();
        safety.block(43, owner);
        service.send(42, common, UUID.randomUUID().toString(), "existing shared group remains visible");
        check(service.history(43, common, null, null, 100).size() == 1, "Block silently hid existing shared group");
        safety.unblock(43, 42);
        System.out.println(
            "PASS MySQL safety: private block/retry/invites, direction isolation, stored evidence, report uniqueness/authorization, versioned review/audit"
        );
    }

    private static void quotaAndReviewRaceChecks(ChatService service, ChatSafetyService safety, JdbcTemplate jdbc)
        throws Exception {
        String direct = service.create(identity(42), List.of(identity(43)), "quota", false).getId();
        var ids = new ArrayList<String>();
        for (int index = 0; index < 24; index++) ids.add(
            service.send(43, direct, UUID.randomUUID().toString(), "quota " + index).getId()
        );
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (String messageId : ids)
                attempts.add(
                    executor.submit(() -> {
                        try {
                            safety.report(42, direct, messageId, "SPAM", "quota race");
                            return true;
                        } catch (IllegalStateException limit) {
                            check(limit.getMessage().contains("20"), "Unexpected quota failure");
                            return false;
                        }
                    })
                );
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            check(accepted == 20, "Concurrent quota exceeded or lost valid reports");
        }
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_report WHERE reporter_id=42", Long.class) == 20,
            "Quota database mismatch"
        );
        var report = safety.mine(42, null, 100).getFirst();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reviewAttempt(safety, 45, report.getId(), "RESOLVED"));
            var second = executor.submit(() -> reviewAttempt(safety, 46, report.getId(), "REJECTED"));
            check((first.get() ? 1 : 0) + (second.get() ? 1 : 0) == 1, "Concurrent review overwrote final decision");
        }
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_report_review WHERE report_id=?",
                Long.class,
                report.getId()
            ) == 1,
            "Concurrent review duplicated audit"
        );
        String rollbackId = safety.mine(42, null, 100).get(1).getId();
        // 隔离库的局部约束注入写入失败，不提升数据库用户权限或修改共享 MySQL 全局设置。
        jdbc.execute(
            "ALTER TABLE chat_report_review ADD CONSTRAINT injected_review_failure CHECK (note <> 'must rollback')"
        );
        try {
            rejects(RuntimeException.class, () -> safety.review(45, rollbackId, 0, "RESOLVED", "must rollback"));
            var unchanged = safety.mine(42, rollbackId);
            check(
                unchanged.getStatus().equals("PENDING") && unchanged.getVersion() == 0,
                "Audit failure did not roll back report"
            );
            check(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_report_review WHERE report_id=?",
                    Long.class,
                    rollbackId
                ) == 0,
                "Audit failure left record"
            );
        } finally {
            jdbc.execute("ALTER TABLE chat_report_review DROP CHECK injected_review_failure");
        }
        System.out.println(
            "PASS MySQL safety races: 24 concurrent reports enforce quota 20, two reviewers only one final audit, audit insert failure rolls back decision/version"
        );
    }

    private static boolean reviewAttempt(ChatSafetyService safety, long reviewerId, String id, String decision) {
        try {
            safety.review(reviewerId, id, 0, decision, "concurrent review");
            return true;
        } catch (IllegalStateException conflict) {
            return false;
        }
    }

    /** 在隔离库预置完整一致的 999 个关系，8 路并发只允许一个跨入 1000 上限。 */
    private static void blockQuotaRaceCheck(ChatSafetyService safety, JdbcTemplate jdbc) throws Exception {
        var facts = new ArrayList<Object[]>();
        var pairs = new ArrayList<Object[]>();
        for (long target = 100000; target < 100999; target++) {
            facts.add(new Object[] { UUID.randomUUID().toString(), 62L, target, "user" + target, "User " + target });
            pairs.add(new Object[] { 62L, target });
        }
        jdbc.batchUpdate(
            "INSERT INTO chat_block(id,owner_id,target_id,target_handle,target_name,created_at) VALUES(?,?,?,?,?,NOW(6))",
            facts
        );
        jdbc.batchUpdate("INSERT INTO chat_contact_lock(low_id,high_id,low_blocks_high) VALUES(?,?,1)", pairs);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (long target = 200000; target < 200008; target++) {
                final long targetId = target;
                attempts.add(
                    executor.submit(() -> {
                        try {
                            safety.block(62, identity(targetId));
                            return true;
                        } catch (IllegalStateException limit) {
                            check(limit.getMessage().contains("1000"), "Unexpected block quota failure");
                            return false;
                        }
                    })
                );
            }
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            check(accepted == 1, "Concurrent blocking exceeded limit or lost the final slot");
        }
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_block WHERE owner_id=62", Long.class) == 1000,
            "Block quota database mismatch"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_contact_lock WHERE low_id=62 AND low_blocks_high=1",
                Long.class
            ) == 1000,
            "Block quota left authorization flags inconsistent"
        );
        System.out.println(
            "PASS MySQL block quota race: 999 existing relations plus 8 concurrent attempts accepts exactly one; rejected attempts leave no pair flags"
        );
    }

    /** 为既有并发验收创建真实 MANDATORY 版本写入代理。 */
    private static ChatSyncWriter syncWriter(SqlSessionTemplate sessions, DriverManagerDataSource source) {
        var proxy = new ProxyFactory(new ChatSyncWriter(sessions.getMapper(ChatSyncMapper.class)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        return (ChatSyncWriter) proxy.getProxy();
    }

    /** 先建立发送事务的 RR 快照，再提交拉黑；验证授权读取不复用旧快照。 */
    private static void staleSnapshotBlockCheck(
        ChatService normal,
        ChatSafetyService safety,
        ChatSafetyService safetyTarget,
        SqlSessionTemplate sessions,
        DriverManagerDataSource source,
        JdbcTemplate jdbc
    ) throws Exception {
        String direct = normal.create(identity(52), List.of(identity(53)), "snapshot race", false).getId();
        var started = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var policyProxy = new ProxyFactory(safetyTarget);
        policyProxy.setProxyTargetClass(true);
        policyProxy.addAdvice(
            (org.aopalliance.intercept.MethodInterceptor) invocation -> {
                if (invocation.getMethod().getName().equals("requireContactAllowed")) {
                    started.countDown();
                    check(resume.await(30, java.util.concurrent.TimeUnit.SECONDS), "Snapshot test timed out");
                }
                return invocation.proceed();
            }
        );
        policyProxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        var raceTarget = new ChatService(
            sessions.getMapper(ConversationMapper.class),
            sessions.getMapper(MemberMapper.class),
            sessions.getMapper(MessageMapper.class),
            (ChatSafetyService) policyProxy.getProxy(),
            syncWriter(sessions, source)
        );
        var senderProxy = new ProxyFactory(raceTarget);
        senderProxy.setProxyTargetClass(true);
        senderProxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        var sender = (ChatService) senderProxy.getProxy();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var attempt = executor.submit(() -> {
                try {
                    sender.send(52, direct, UUID.randomUUID().toString(), "must not commit after block");
                    return false;
                } catch (ForbiddenOperationException denied) {
                    return true;
                }
            });
            try {
                check(started.await(30, java.util.concurrent.TimeUnit.SECONDS), "Sender did not reach authorization");
                safety.block(53, identity(52));
            } finally {
                resume.countDown();
            }
            check(attempt.get(30, java.util.concurrent.TimeUnit.SECONDS), "Old RR snapshot bypassed new block");
        }
        check(
            jdbc.queryForObject("SELECT last_seq FROM chat_conversation WHERE id=?", Long.class, direct) == 0,
            "Blocked race consumed sequence"
        );
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?", Long.class, direct) == 0,
            "Blocked race persisted message"
        );
        System.out.println(
            "PASS MySQL authorization race: committed block wins even after sender has an older RR snapshot; no message/sequence committed"
        );
    }

    private static ChatIdentity identity(long id) {
        return new ChatIdentity(Long.toString(id), "user" + id, "User " + id);
    }

    private static void archiveChecks(ChatService service, ChatArchiveService archive, JdbcTemplate jdbc)
        throws Exception {
        String id = service.create(identity(72), List.of(identity(73)), "archive", false).getId();
        var seed = new ArrayList<Object[]>();
        for (long seq = 1; seq <= 2205; seq++) {
            seed.add(new Object[] {
                UUID.randomUUID().toString(),
                id,
                seq,
                72L,
                "User 72",
                UUID.randomUUID().toString(),
                seq <= 3 ? "literal 100%_= 中文 needle" : "noise",
            });
        }
        jdbc.batchUpdate(
            "INSERT INTO chat_message(id,conversation_id,seq,sender_id,sender_name,client_message_id,body) VALUES(?,?,?,?,?,?,?)",
            seed
        );
        jdbc.update("UPDATE chat_conversation SET last_seq=2205 WHERE id=?", id);
        var first = archive.search(73, id, "needle", null, 2);
        check(first.items().isEmpty() && first.nextBefore() == 206, "Empty search lost earlier scan cursor");
        var second = archive.search(73, id, "needle", first.nextBefore(), 2);
        check(
            second.items().size() == 2 && second.items().getFirst().getSeq() == 3 && second.nextBefore() == 2,
            "Search pagination skips or duplicates matches"
        );
        var tail = archive.search(73, id, "needle", second.nextBefore(), 2);
        check(
            tail.items().size() == 1 && tail.items().getFirst().getSeq() == 1 && tail.nextBefore() == null,
            "Search cursor did not finish"
        );
        check(archive.search(73, id, "100%_=", 206L, 20).items().size() == 3, "Literal wildcard escaping failed");
        check(archive.search(73, id, "中文", 206L, 20).items().size() == 3, "Chinese literal search failed");
        check(archive.search(73, id, "NEEDLE", 206L, 20).items().isEmpty(), "Case-sensitive contract changed");
        check(archive.search(73, id, "' OR 1=1 --", null, 20).items().isEmpty(), "Search input changed SQL semantics");
        rejects(ResourceNotFoundException.class, () -> archive.search(74, id, "needle", null, 20));
        rejects(IllegalArgumentException.class, () -> archive.bookmarked(73, id, 99999L, 20));
        String messageId = (String) seed.getFirst()[0];
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<?>>();
            for (int attempt = 0; attempt < 16; attempt++) attempts.add(
                executor.submit(() -> archive.save(73, id, messageId))
            );
            for (var attempt : attempts) attempt.get();
        }
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM chat_bookmark WHERE owner_id=73", Long.class) == 1,
            "Bookmark retry race duplicated records"
        );
        check(archive.bookmarked(72, id, null, 20).items().isEmpty(), "Other member sees someone's bookmarks");
        archive.save(73, id, (String) seed.get(1)[0]);
        archive.save(73, id, (String) seed.get(2)[0]);
        var saved = archive.bookmarked(73, id, null, 2);
        check(
            saved.items().size() == 2 &&
                saved.nextBefore() == 2 &&
                archive.bookmarked(73, id, saved.nextBefore(), 2).items().getFirst().getSeq() == 1,
            "Bookmark pagination boundary failed"
        );
        archive.remove(72, id, messageId);
        check(archive.bookmarked(73, id, null, 20).items().size() == 3, "Other user's cancel changed owner's settings");
        archive.remove(73, id, messageId);
        archive.remove(73, id, messageId);
        check(archive.bookmarked(73, id, null, 20).items().size() == 2, "Cancel retry not idempotent");
        var quotaSeed = new ArrayList<Object[]>();
        for (int index = 0; index < 999; index++) quotaSeed.add(new Object[] {
            UUID.randomUUID().toString(),
            72L,
            id,
            seed.get(index)[0],
            index + 1,
        });
        jdbc.batchUpdate(
            "INSERT INTO chat_bookmark(id,owner_id,conversation_id,message_id,message_seq,created_at) VALUES(?,?,?,?,?,NOW(6))",
            quotaSeed
        );
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (int index = 999; index < 1007; index++) {
                String candidate = (String) seed.get(index)[0];
                attempts.add(
                    executor.submit(() -> {
                        try {
                            archive.save(72, id, candidate);
                            return true;
                        } catch (IllegalStateException limit) {
                            check(limit.getMessage().contains("1000"), "Unexpected bookmark quota error");
                            return false;
                        }
                    })
                );
            }
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            check(accepted == 1, "Bookmark capacity race exceeded 1000");
        }
        archive.clear(72, id);
        archive.clear(72, id);
        check(
            archive.bookmarked(72, id, null, 20).items().isEmpty() &&
                archive.bookmarked(73, id, null, 20).items().size() == 2,
            "Clearing changed other user or failed idempotence"
        );
        String group = service.create(identity(72), List.of(identity(73)), "archive boundary", true).getId();
        var old = service.send(72, group, UUID.randomUUID().toString(), "needle before join");
        archive.save(73, group, old.getId());
        service.remove(72, group, 73);
        rejects(ResourceNotFoundException.class, () -> archive.bookmarked(73, group, null, 20));
        rejects(ResourceNotFoundException.class, () -> archive.search(73, group, "needle", null, 20));
        service.add(72, group, identity(73));
        check(archive.bookmarked(73, group, null, 20).items().isEmpty(), "Rejoin reveals prior bookmark body");
        check(archive.search(73, group, "needle", null, 20).items().isEmpty(), "Rejoin search reveals old body");
        rejects(ResourceNotFoundException.class, () -> archive.save(73, group, old.getId()));
        rejects(ResourceNotFoundException.class, () -> archive.save(73, group, messageId));
        var fresh = service.send(72, group, UUID.randomUUID().toString(), "needle after rejoin");
        archive.save(73, group, fresh.getId());
        archive.clear(73, group);
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_bookmark WHERE owner_id=73 AND conversation_id=?",
                Long.class,
                group
            ) == 0,
            "Clear did not remove unreadable old references"
        );
        service.close(72, group);
        rejects(ResourceNotFoundException.class, () -> archive.search(73, group, "needle", null, 20));
        rejects(ResourceNotFoundException.class, () -> archive.save(73, group, fresh.getId()));
        System.out.println(
            "PASS MySQL history tools: bounded 2205-message scan, empty-window cursor, literal/Chinese/case search, bookmark isolation/16 retries/8 quota races, cancel/clear, removed/rejoin/closed boundaries"
        );
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException error) {
            if (type.isInstance(error)) return;
            throw error;
        }
        throw new IllegalStateException("Expected " + type.getSimpleName());
    }
}
