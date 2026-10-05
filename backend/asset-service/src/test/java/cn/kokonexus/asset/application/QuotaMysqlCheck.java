package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.AssetUsageMapper;
import cn.kokonexus.asset.infrastructure.persistence.MediaAssetMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Explicitly run against a disposable MySQL schema; never starts a production service or RPC provider. */
public final class QuotaMysqlCheck {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("QUOTA_CHECK_JDBC");
        if (url == null || !url.matches("jdbc:mysql://mysql:3306/koko_quota_check_[0-9]+\\?.+")) {
            throw new IllegalArgumentException("A named disposable schema is required");
        }
        var dataSource = new DriverManagerDataSource(
            url,
            System.getenv("QUOTA_CHECK_USER"),
            System.getenv("QUOTA_CHECK_PASSWORD")
        );
        var jdbc = new JdbcTemplate(dataSource);
        for (String file : List.of(
            "V1__media_asset.sql",
            "V2__asset_library_index.sql",
            "V3__asset_upload_quota.sql"
        )) {
            try (var stream = QuotaMysqlCheck.class.getResourceAsStream("/db/migration/" + file)) {
                if (stream == null) throw new IllegalStateException("Missing migration " + file);
                String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
                for (String statement : source.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            }
        }
        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        var factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(AssetUsageMapper.class);
        factory.getConfiguration().addMapper(MediaAssetMapper.class);
        var sessions = new SqlSessionTemplate(factory);
        var manager = new DataSourceTransactionManager(dataSource);
        var service = transactional(sessions, manager, 600, 3, 1200, 6);

        int accepted = 0;
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 16; index++) requests.add(
                pool.submit(() -> {
                    try {
                        service.register(pending(42));
                        return true;
                    } catch (AssetQuotaExceededException expected) {
                        return false;
                    }
                })
            );
            for (var result : requests) if (result.get()) accepted++;
        }
        check(accepted == 3, "Concurrent owner reservations exceeded or lost quota");
        check(service.quota(42).usedImages() == 3 && service.quota(42).usedBytes() == 600, "Owner accounting mismatch");
        check(bucketCount(jdbc) == 3, "Rejected owner allocations leaked bucket quota");

        String existingId = jdbc.queryForObject("SELECT id FROM media_asset LIMIT 1", String.class);
        MediaAsset duplicate = pending(43);
        duplicate.setId(existingId);
        try {
            service.register(duplicate);
            throw new IllegalStateException("Duplicate registration should fail");
        } catch (org.springframework.dao.DuplicateKeyException expected) {}
        check(bucketCount(jdbc) == 3 && service.quota(43).usedImages() == 0, "Failed insert did not roll back budgets");

        for (int index = 0; index < 3; index++) service.register(pending(43));
        try {
            service.register(pending(44));
            throw new IllegalStateException("Bucket overflow should fail");
        } catch (AssetQuotaExceededException expected) {
            check(expected.sharedCapacity(), "Wrong limit reported");
        }
        check(bucketCount(jdbc) == 6 && service.quota(44).usedImages() == 0, "Bucket overflow changed usage");

        MediaAsset cleaning = sessions.getMapper(MediaAssetMapper.class).selectById(existingId);
        jdbc.update("UPDATE media_asset SET status='CLEANING' WHERE id=?", existingId);
        service.deleteCleaning(cleaning);
        service.deleteCleaning(cleaning);
        check(bucketCount(jdbc) == 5 && service.quota(42).usedImages() == 2, "Repeated cleanup released quota twice");
        check(
            jdbc.queryForObject("SELECT byte_size FROM asset_usage WHERE owner_id=0", Long.class) == 1000,
            "Bucket byte accounting mismatch"
        );
        check(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset", Long.class) == 5, "Intent count mismatch");
        clearFixtures(sessions, jdbc, service);
        var bytesOnly = transactional(sessions, manager, 500, 10, 5000, 100);
        bytesOnly.register(pending(51));
        bytesOnly.register(pending(51));
        rejects(bytesOnly, 51, false);
        check(bytesOnly.quota(51).usedBytes() == 400, "Byte-only owner limit failed");
        clearFixtures(sessions, jdbc, bytesOnly);
        var countOnly = transactional(sessions, manager, 5000, 2, 10000, 4);
        countOnly.register(pending(51));
        countOnly.register(pending(51));
        rejects(countOnly, 51, false);
        check(countOnly.quota(51).usedImages() == 2, "Count-only owner limit failed");
        clearFixtures(sessions, jdbc, countOnly);
        var bucketCountOnly = transactional(sessions, manager, 5000, 1, 10000, 2);
        bucketCountOnly.register(pending(51));
        bucketCountOnly.register(pending(52));
        rejects(bucketCountOnly, 53, true);
        clearFixtures(sessions, jdbc, bucketCountOnly);
        var bucketBytesOnly = transactional(sessions, manager, 500, 10, 600, 100);
        bucketBytesOnly.register(pending(51));
        bucketBytesOnly.register(pending(52));
        bucketBytesOnly.register(pending(53));
        rejects(bucketBytesOnly, 54, true);
        clearFixtures(sessions, jdbc, bucketBytesOnly);
        System.out.println(
            "PASS MySQL quota: 16 concurrent uploads, separate owner/bucket byte/count limits, insert rollback, idempotent release"
        );
    }

    private static long bucketCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT image_count FROM asset_usage WHERE owner_id=0", Long.class);
    }

    private static AssetRegistrationService transactional(
        SqlSessionTemplate sessions,
        DataSourceTransactionManager manager,
        long ownerBytes,
        long ownerCount,
        long bucketBytes,
        long bucketCount
    ) {
        var target = new AssetRegistrationService(
            sessions.getMapper(AssetUsageMapper.class),
            sessions.getMapper(MediaAssetMapper.class),
            ownerBytes,
            ownerCount,
            bucketBytes,
            bucketCount
        );
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (AssetRegistrationService) proxy.getProxy();
    }

    private static void clearFixtures(
        SqlSessionTemplate sessions,
        JdbcTemplate jdbc,
        AssetRegistrationService service
    ) {
        for (var image : sessions
            .getMapper(MediaAssetMapper.class)
            .selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.query())) {
            jdbc.update("UPDATE media_asset SET status='CLEANING' WHERE id=?", image.getId());
            service.deleteCleaning(image);
        }
        check(
            bucketCount(jdbc) == 0 &&
                jdbc.queryForObject("SELECT byte_size FROM asset_usage WHERE owner_id=0", Long.class) == 0,
            "Fixture cleanup did not release full bucket usage"
        );
    }

    private static void rejects(AssetRegistrationService service, long owner, boolean shared) {
        try {
            service.register(pending(owner));
            throw new IllegalStateException("Quota overflow should fail");
        } catch (AssetQuotaExceededException expected) {
            check(expected.sharedCapacity() == shared, "Wrong quota scope");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static MediaAsset pending(long owner) {
        var asset = new MediaAsset();
        asset.setId(UUID.randomUUID().toString());
        asset.setOwnerId(owner);
        asset.setPurpose("AVATAR");
        asset.setObjectKey("images/" + owner + "/" + asset.getId() + ".png");
        asset.setContentType("image/png");
        asset.setByteSize(200L);
        asset.setWidth(2);
        asset.setHeight(2);
        asset.setSha256("0".repeat(64));
        asset.setStatus("PENDING");
        asset.setCreatedAt(LocalDateTime.now());
        return asset;
    }
}
