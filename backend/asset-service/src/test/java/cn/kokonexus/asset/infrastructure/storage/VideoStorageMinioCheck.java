package cn.kokonexus.asset.infrastructure.storage;

import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** 显式运行的真实隔离MinIO检查；只创建全新命名桶，不接受生产桶，不把协议兼容当安全升级。 */
public final class VideoStorageMinioCheck {

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            throw new IllegalStateException("隔离MinIO验收未通过；类别=" + failure.getClass().getSimpleName());
        }
    }

    private static void run() throws Exception {
        String endpoint = required("VIDEO_MINIO_CHECK_ENDPOINT"),
            bucket = required("VIDEO_MINIO_CHECK_BUCKET");
        check(endpoint.matches("http://127\\.0\\.0\\.1:[1-9][0-9]{3,4}"), "Owned loopback tunnel required");
        check(bucket.matches("koko-video-check-[a-f0-9]{16}"), "Fresh owned isolated bucket required");
        check(
            required("VIDEO_MINIO_CHECK_SCOPE").equals("OWNED_ISOLATED_INSTANCE_NOT_PRODUCTION"),
            "Explicit isolated instance required"
        );
        var admin = client(
            endpoint,
            required("VIDEO_MINIO_CHECK_ROOT_ACCESS"),
            required("VIDEO_MINIO_CHECK_ROOT_SECRET")
        );
        var limited = client(endpoint, required("VIDEO_MINIO_CHECK_ACCESS"), required("VIDEO_MINIO_CHECK_SECRET"));
        var storage = new MinioVideoStorage(
            endpoint,
            bucket,
            required("VIDEO_MINIO_CHECK_ACCESS"),
            required("VIDEO_MINIO_CHECK_SECRET")
        );
        var anonymous = MinioClient.builder().endpoint(endpoint).httpClient(http()).build();
        String other = bucket + "-other",
            id = UUID.randomUUID().toString(),
            key = "videos/42/" + id + "/source.mp4",
            neighbor = key + "-other",
            image = "images/42/synthetic.png";
        check(!exists(admin, bucket) && !exists(admin, other), "Refuse pre-existing bucket");
        Path work = Path.of(required("VIDEO_MINIO_CHECK_WORK")).toAbsolutePath();
        check(
            work.startsWith(Path.of("D:/KOKO/.runtime")) && !Files.exists(work),
            "Fresh private D runtime evidence required"
        );
        Files.createDirectories(work);
        byte[] bytes = new byte[1024];
        for (int n = 0; n < bytes.length; n++) bytes[n] = (byte) n;
        Path file = work.resolve("synthetic-only.bin");
        Files.write(file, bytes);
        var groups = new ArrayList<String>();
        var written = new LinkedHashSet<String>();
        boolean created = false,
            otherCreated = false,
            cleaned = false;
        String failureType = null;
        try {
            admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            created = true;
            admin.makeBucket(MakeBucketArgs.builder().bucket(other).build());
            otherCreated = true;
            written.add(key);
            storage.put(key, file);
            try (var in = storage.open(key, 10, 10)) {
                check(
                    java.util.Arrays.equals(java.util.Arrays.copyOfRange(bytes, 10, 20), in.readAllBytes()),
                    "Real S3 Range differs"
                );
            }
            denied(() -> anonymous.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()));
            groups.add("REAL_MINIO_PRIVATE_PUT_GET_EXACT_RANGE_ANONYMOUS_403");

            written.add(image);
            put(admin, bucket, image, bytes);
            dependency(() -> storage.open(image, 0, 16));
            dependency(() -> storage.put(image, file));
            denied(() -> limited.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(image).build()));
            denied(() -> limited.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()));
            denied(() ->
                limited
                    .listObjects(
                        ListObjectsArgs.builder().bucket(bucket).prefix("images/").includeVersions(true).build()
                    )
                    .iterator()
                    .next()
                    .get()
            );
            dependency(() ->
                new MinioVideoStorage(
                    endpoint,
                    other,
                    required("VIDEO_MINIO_CHECK_ACCESS"),
                    required("VIDEO_MINIO_CHECK_SECRET")
                ).put(key, file)
            );
            try (var in = admin.getObject(GetObjectArgs.builder().bucket(bucket).object(image).build())) {
                check(java.util.Arrays.equals(bytes, in.readAllBytes()), "Image sentinel modified");
            }
            groups.add(
                "ACTUAL_VIDEO_IAM_DENIES_IMAGES_OTHER_BUCKET_VERSION_CONFIG_AND_IMAGE_VERSION_LIST_SENTINEL_UNCHANGED"
            );

            storage.removeAndConfirm(key, deadline());
            absent(admin, bucket, key);
            storage.removeAndConfirm(key, deadline());
            groups.add("UNVERSIONED_NULL_VERSION_DELETE_AND_IDEMPOTENT_ABSENCE_VERIFIED_BY_ADMIN");
            versioning(admin, bucket, VersioningConfiguration.Status.ENABLED);
            storage.put(key, file);
            storage.put(key, file);
            written.add(neighbor);
            put(admin, bucket, neighbor, bytes);
            admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            var before = versions(admin, bucket, key);
            check(
                before.size() == 3 && before.stream().anyMatch(Version::marker),
                "Real delete marker fixture missing"
            );
            storage.removeAndConfirm(key, deadline());
            absent(admin, bucket, key);
            check(
                admin.statObject(StatObjectArgs.builder().bucket(bucket).object(neighbor).build()).size() ==
                    bytes.length,
                "Similar prefix object deleted"
            );
            groups.add("VERSIONED_MULTIPLE_VERSIONS_AND_DELETE_MARKER_REMOVED_EXACT_KEY_NOT_SIMILAR_PREFIX");

            storage.put(key, file);
            versioning(admin, bucket, VersioningConfiguration.Status.SUSPENDED);
            storage.put(key, file);
            check(versions(admin, bucket, key).size() == 2, "Suspended null-version fixture missing");
            storage.removeAndConfirm(key, deadline());
            absent(admin, bucket, key);
            groups.add("SUSPENDED_BUCKET_NULL_AND_NON_NULL_VERSIONS_BOTH_REMOVED");

            versioning(admin, bucket, VersioningConfiguration.Status.ENABLED);
            for (int n = 0; n < 9; n++) put(admin, bucket, key, bytes);
            dependency(() -> storage.removeAndConfirm(key, deadline()));
            check(versions(admin, bucket, key).size() == 9, "Over-limit cleanup mutated before refusing");
            dependency(() -> storage.removeAndConfirm(key, System.nanoTime() - 1));
            check(versions(admin, bucket, key).size() == 9, "Expired budget mutated objects");
            groups.add("NINE_VERSION_BOUND_AND_EXPIRED_DEADLINE_FAIL_CLOSED_WITHOUT_PARTIAL_DELETION");
            // 隔离合成字节及版本目录留证，再清理；没有任何生产对象或凭据进入报告。
            Files.writeString(
                work.resolve("synthetic-backup-manifest.json"),
                new ObjectMapper().writeValueAsString(
                    Map.of(
                        "onlySynthetic",
                        true,
                        "fixtureFile",
                        file.getFileName().toString(),
                        "versions",
                        versions(admin, bucket, "")
                    )
                )
            );
        } catch (Throwable failure) {
            failureType = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            try {
                if (created) {
                    for (var version : versions(admin, bucket, "")) {
                        check(written.contains(version.key()), "Foreign key in isolated bucket; retain data for audit");
                        admin.removeObject(
                            RemoveObjectArgs.builder()
                                .bucket(bucket)
                                .object(version.key())
                                .versionId(version.id() == null ? "null" : version.id())
                                .build()
                        );
                    }
                    check(versions(admin, bucket, "").isEmpty(), "Owned bucket versions remain");
                    admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
                }
                if (otherCreated) {
                    check(
                        versions(admin, other, "").isEmpty(),
                        "Denied other bucket unexpectedly gained an object; retain for audit"
                    );
                    admin.removeBucket(RemoveBucketArgs.builder().bucket(other).build());
                }
                cleaned = !exists(admin, bucket) && !exists(admin, other);
                check(cleaned, "Owned bucket cleanup unconfirmed");
            } finally {
                var report = Map.of(
                    "passed",
                    failureType == null && cleaned,
                    "realMinio",
                    true,
                    "productionObjectsAccessed",
                    false,
                    "ownedBucketsRemoved",
                    cleaned,
                    "groups",
                    groups,
                    "failureType",
                    failureType == null ? "none" : failureType,
                    "securityUpgradeAcceptance",
                    false
                );
                String json = new ObjectMapper().writeValueAsString(report);
                Files.writeString(work.resolve("verdict.json"), json);
                System.out.println(json);
            }
        }
    }

    /** 只保留测试对象键和版本ID，不保留签名请求或凭据。 */
    public record Version(
        /** 合成对象键。 */ String key,
        /** null版本使用字面值null。 */ String id,
        /** 是否删除标记。 */ boolean marker
    ) {}

    private static List<Version> versions(MinioClient client, String bucket, String prefix) throws Exception {
        var rows = new ArrayList<Version>();
        for (var value : client.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .includeVersions(true)
                .maxKeys(32)
                .build()
        )) {
            check(rows.size() < 64, "Owned version inventory exceeded bound");
            var item = value.get();
            if (prefix.isEmpty() || item.objectName().equals(prefix)) rows.add(
                new Version(item.objectName(), item.versionId(), item.isDeleteMarker())
            );
        }
        return rows;
    }

    private static void absent(MinioClient client, String bucket, String key) throws Exception {
        check(versions(client, bucket, key).isEmpty(), "Admin still sees exact-key versions");
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            throw new AssertionError("Admin still sees object");
        } catch (ErrorResponseException absent) {
            check(
                absent.response().code() == 404 && absent.errorResponse().code().equals("NoSuchKey"),
                "Not proven absent"
            );
        }
    }

    private static void put(MinioClient client, String bucket, String key, byte[] bytes) throws Exception {
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                .build()
        );
    }

    private static void versioning(MinioClient client, String bucket, VersioningConfiguration.Status status)
        throws Exception {
        client.setBucketVersioning(
            SetBucketVersioningArgs.builder()
                .bucket(bucket)
                .config(new VersioningConfiguration(status, null, null, null))
                .build()
        );
    }

    private static boolean exists(MinioClient client, String bucket) throws Exception {
        return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    private static MinioClient client(String endpoint, String access, String secret) {
        return MinioClient.builder().endpoint(endpoint).credentials(access, secret).httpClient(http()).build();
    }

    private static OkHttpClient http() {
        return new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(10))
            .retryOnConnectionFailure(false)
            .build();
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        check(value != null && !value.isBlank(), "Missing owned MinIO fixture input " + name);
        return value;
    }

    private static void dependency(Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected dependency denial");
        } catch (ExternalDependencyUnavailableException expected) {
            check(expected.getCause() == null, "Sensitive provider exception retained");
        }
    }

    private static void denied(Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected real IAM denial");
        } catch (ErrorResponseException expected) {
            check(
                expected.response().code() == 403 && expected.errorResponse().code().equals("AccessDenied"),
                "Wrong permission rejection"
            );
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
