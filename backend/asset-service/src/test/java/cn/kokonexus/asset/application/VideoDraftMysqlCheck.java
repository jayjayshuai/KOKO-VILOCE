package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.persistence.VideoDraftMapper;
import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.util.unit.DataSize;

/** 独立MySQL/生产MP XML/真实事务代理与真实FFmpeg；仅使用合成视频，不访问生产。 */
public final class VideoDraftMysqlCheck {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("NATIVE_CALL_CHECK_JDBC"),
            user = System.getenv("NATIVE_CALL_CHECK_USER");
        check(
            url != null &&
                url.startsWith("jdbc:mysql://127.0.0.1:33080/koko_native_call_check_20261015?") &&
                "koko_native_call_check_20261015".equals(user),
            "Owned isolated JDBC required"
        );
        var source = new DriverManagerDataSource(url, user, System.getenv("NATIVE_CALL_CHECK_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var baseline = Flyway.configure()
            .dataSource(source)
            .table("asset_flyway_schema_history")
            .locations("classpath:db/migration")
            .target("5")
            .load();
        check(baseline.migrate().migrationsExecuted == 5, "Fresh asset V1-V5 baseline required");
        String oldImage = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO media_asset(id,owner_id,purpose,object_key,content_type,byte_size,width,height,sha256,status) VALUES(?,900,'POST_COVER',?,'image/png',64,2,2,?,'READY')",
            oldImage,
            "images/900/" + oldImage + ".png",
            "a".repeat(64)
        );
        jdbc.update("INSERT INTO asset_usage(owner_id,image_count,byte_size) VALUES(900,1,64)");
        jdbc.update("UPDATE asset_usage SET image_count=1,byte_size=64 WHERE owner_id=0");
        var flyway = Flyway.configure()
            .dataSource(source)
            .table("asset_flyway_schema_history")
            .locations("classpath:db/migration")
            .load();
        check(flyway.migrate().migrationsExecuted == 1, "Existing asset V5 facts must upgrade only V6");
        flyway.validate();
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_asset WHERE id=? AND status='READY' AND byte_size=64 AND owner_id=900",
                Integer.class,
                oldImage
            ) == 1,
            "V6 changed an existing image fact"
        );
        check(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM asset_usage WHERE owner_id IN (0,900) AND image_count=1 AND byte_size=64",
                Integer.class
            ) == 2,
            "V6 changed existing image quota facts"
        );
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(VideoDraftMapper.class);
        var proxy = new ProxyFactory(new VideoDraftState(mapper));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            new TransactionInterceptor(
                new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()
            )
        );
        var state = (VideoDraftState) proxy.getProxy();
        var groups = new java.util.ArrayList<String>();
        groups.add("EXISTING_ASSET_V5_IMAGE_AND_QUOTA_FACTS_PRESERVED_BY_V6_UPGRADE_AND_FLYWAY_VALIDATE");
        String request = UUID.randomUUID().toString();
        var initial = input(101, request);
        var registered = state.register(initial);
        check(state.register(initial).getId().equals(registered.getId()), "Original request duplicate");
        var altered = input(101, request);
        altered.setTitle("changed");
        expect(IllegalStateException.class, () -> state.register(altered));
        check(
            jdbc.queryForObject("SELECT draft_count FROM video_capacity WHERE owner_id=0", Integer.class) == 1,
            "Original request reserved twice"
        );
        expect(ResourceNotFoundException.class, () -> state.owned(102, registered.getId()));
        groups.add("REAL_FLYWAY_V1_V6_ORIGINAL_OWNER_CONTENT_FENCE_NO_DUPLICATE_RESERVATION_AND_OTHER_OWNER_404");
        for (int n = 1; n < 5; n++) state.register(input(101, UUID.randomUUID().toString()));
        int before = jdbc.queryForObject("SELECT draft_count FROM video_capacity WHERE owner_id=0", Integer.class);
        expect(IllegalStateException.class, () -> state.register(input(101, UUID.randomUUID().toString())));
        check(
            jdbc.queryForObject("SELECT draft_count FROM video_capacity WHERE owner_id=0", Integer.class) == before,
            "Failed owner budget leaked global reservation"
        );
        groups.add("REAL_OWNER_CAPACITY_FAILURE_ROLLS_BACK_GLOBAL_AND_INTENT");
        state.queued(101, registered.getId());
        var barrier = new CyclicBarrier(2);
        var claimed = new java.util.ArrayList<VideoDraft>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return state.claim();
            });
            var two = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return state.claim();
            });
            var a = one.get(10, TimeUnit.SECONDS);
            var b = two.get(10, TimeUnit.SECONDS);
            if (a != null) claimed.add(a);
            if (b != null) claimed.add(b);
        }
        check(claimed.size() == 1, "Concurrent global processing slot duplicated");
        var job = claimed.getFirst();
        check(state.canProcess(job), "Fresh lease has insufficient initial budget");
        job.setOutputBytes(100L);
        job.setDurationMillis(1000L);
        job.setWidth(320);
        job.setHeight(180);
        var wrong = input(101, request);
        wrong.setId(job.getId());
        wrong.setRunId(UUID.randomUUID().toString());
        wrong.setOutputBytes(100L);
        wrong.setDurationMillis(1000L);
        wrong.setWidth(320);
        wrong.setHeight(180);
        check(!state.ready(wrong), "Wrong worker token committed");
        jdbc.update("UPDATE video_draft SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", job.getId());
        check(!state.canProcess(job) && !state.ready(job), "Expired worker started or completed");
        state.claim();
        check(state.owned(101, job.getId()).getState().equals("FAILED"), "Expired task did not fail closed");
        check(
            jdbc.queryForObject("SELECT draft_count FROM video_capacity WHERE owner_id=101", Integer.class) == 5,
            "Failed task released unverified object budget"
        );
        groups.add(
            "CONCURRENT_REAL_SQL_CLAIM_ONE_GLOBAL_SLOT_EXPIRED_AND_WRONG_RUN_FENCED_FAILED_STORAGE_BUDGET_RETAINED"
        );
        for (int n = 0; n < 5; n++) state.register(input(102, UUID.randomUUID().toString()));
        expect(IllegalStateException.class, () -> state.register(input(103, UUID.randomUUID().toString())));
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM video_draft", Integer.class) == 10,
            "Global capacity left partial intent"
        );
        jdbc.update("UPDATE video_draft SET created_at=UTC_TIMESTAMP(6)-INTERVAL 16 MINUTE WHERE state='UPLOADING'");
        state.claim();
        check(
            jdbc.queryForObject("SELECT COUNT(*) FROM video_draft WHERE state='UPLOADING'", Integer.class) == 0,
            "Stale upload remained live"
        );
        groups.add("GLOBAL_MAX_TEN_RESERVATIONS_AND_INTERRUPTED_UPLOAD_FAILED_WITHOUT_PHYSICAL_CLEANUP_CLAIM");

        // 下列全新预算只属于本隔离库：保留前面事实，但允许验证新视频的真实处理。
        jdbc.update("UPDATE video_capacity SET draft_count=0");
        Path media = Path.of(required("VIDEO_CHECK_SOURCE")),
            work = Path.of(required("VIDEO_CHECK_WORK"));
        var multipart = new MultipartProperties();
        multipart.setMaxFileSize(DataSize.ofMegabytes(32));
        multipart.setMaxRequestSize(DataSize.ofMegabytes(33));
        multipart.setLocation(work.toString());
        var files = new VideoFiles(
            work.toString(),
            required("VIDEO_CHECK_FFMPEG"),
            required("VIDEO_CHECK_FFPROBE"),
            multipart
        );
        Path objectRoot = work.resolve("synthetic-object-store");
        Files.createDirectories(objectRoot);
        VideoStorage storage = new VideoStorage() {
            public void put(String key, Path file) {
                try {
                    Path target = objectRoot.resolve(key);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }

            public java.io.InputStream open(String key, long offset, long length) {
                try {
                    var stream = Files.newInputStream(objectRoot.resolve(key));
                    stream.skipNBytes(offset);
                    return stream;
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }
        };
        var uploads = new VideoDraftService(state, storage, files);
        var processor = new VideoProcessor(files, new ObjectMapper());
        var worker = new VideoDraftWorker(state, storage, processor, files);
        byte[] bytes = Files.readAllBytes(media);
        String original = UUID.randomUUID().toString();
        var queued = uploads.upload(
            201,
            original,
            "合成视频",
            "isolated test",
            new MockMultipartFile("file", "test.mp4", "video/mp4", bytes)
        );
        check(queued.getState().equals("QUEUED"), "Upload did not queue actual source");
        check(
            uploads
                .upload(
                    201,
                    original,
                    "合成视频",
                    "isolated test",
                    new MockMultipartFile("file", "test.mp4", "video/mp4", bytes)
                )
                .getId()
                .equals(queued.getId()),
            "Original upload created duplicate source intent"
        );
        worker.work();
        var ready = state.owned(201, queued.getId());
        check(
            ready.getState().equals("READY") &&
                ready.getOutputBytes() > 16 &&
                ready.getDurationMillis() >= 1000 &&
                ready.getWidth() <= 1280 &&
                ready.getHeight() <= 720,
            "Real FFmpeg output not ready"
        );
        Path output = objectRoot.resolve(VideoDraftService.outputKey(ready));
        check(Files.size(output) == ready.getOutputBytes(), "Stored output differs from READY facts");
        groups.add(
            "REAL_SYNTHETIC_MP4_UPLOAD_SHA_PRIVATE_FILE_STORE_ACTUAL_FFPROBE_FFMPEG_READY_METADATA_AND_EXACT_ORIGINAL_REPLAY"
        );
        byte[] invalid = new byte[64];
        System.arraycopy("ftyp".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, invalid, 4, 4);
        var corrupt = uploads.upload(
            202,
            UUID.randomUUID().toString(),
            "invalid",
            "",
            new MockMultipartFile("file", "bad.mp4", "video/mp4", invalid)
        );
        worker.work();
        var failed = state.owned(202, corrupt.getId());
        check(
            failed.getState().equals("FAILED") && failed.getFailureCode().equals("INVALID_VIDEO"),
            "Invalid MP4 masqueraded as ready"
        );
        VideoDraftBrowserHarness.run(uploads, state, storage, worker, work, media);
        check(
            jdbc.queryForObject("SELECT draft_count FROM video_capacity WHERE owner_id=201", Integer.class) == 2,
            "Actual browser original upload retry reserved more than once"
        );
        worker.close();
        groups.add("CORRUPT_FTYP_HEADER_REJECTED_BY_REAL_FFPROBE_NEVER_READY_NO_AUTOMATIC_RETRY");
        groups.add(
            "ACTUAL_TOMCAT_BROWSER_UPLOAD_UNKNOWN_ORIGINAL_RETRY_PROCESS_READY_PLAYBACK_SEEK_AND_OTHER_OWNER_404_WITH_EXPLICIT_TEST_IDENTITY_AND_FILE_STORAGE"
        );
        System.out.println(
            new ObjectMapper().writeValueAsString(
                java.util.Map.of(
                    "passed",
                    true,
                    "realMysql",
                    true,
                    "realFfmpeg",
                    true,
                    "storageAdapter",
                    "explicit-isolated-files-not-MinIO",
                    "productionAccessed",
                    false,
                    "groups",
                    groups,
                    "readyOutput",
                    output.toString()
                )
            )
        );
    }

    private static String required(String key) {
        String value = System.getenv(key);
        check(value != null && !value.isBlank(), "Missing isolated media input " + key);
        return value;
    }

    private static VideoDraft input(long owner, String request) {
        var row = new VideoDraft();
        row.setId(UUID.randomUUID().toString());
        row.setOwnerId(owner);
        row.setRequestId(request);
        row.setTitle("synthetic");
        row.setDescription("");
        row.setSourceBytes(64L);
        row.setSourceSha256("a".repeat(64));
        return row;
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected rejection");
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError("Unexpected failure", failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
