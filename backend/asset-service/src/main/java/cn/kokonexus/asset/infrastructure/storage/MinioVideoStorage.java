package cn.kokonexus.asset.infrastructure.storage;

import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 既有私有MinIO桶中的视频专用前缀；不生成预签名公网URL。 */
@Component
@ConditionalOnProperty(prefix = "koko.video", name = "enabled", havingValue = "true")
public class MinioVideoStorage implements VideoStorage {

    /** 单请求连接/读写/整体超时有界，不启用连接失败隐式重试。 */ private final MinioClient client;
    /** 已有私有资产桶，运维须单独核对无公共读策略。 */ private final String bucket;

    public MinioVideoStorage(
        @Value("${koko.video.endpoint}") String endpoint,
        @Value("${koko.video.bucket}") String bucket,
        @Value("${koko.video.access-key}") String access,
        @Value("${koko.video.secret-key}") String secret
    ) {
        if (access.isBlank() || secret.isBlank()) throw new IllegalStateException("视频存储凭据未配置");
        client = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(access, secret)
            .httpClient(
                new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(20))
                    .writeTimeout(Duration.ofSeconds(20))
                    .callTimeout(Duration.ofSeconds(20))
                    .retryOnConnectionFailure(false)
                    .build()
            )
            .build();
        this.bucket = bucket;
    }

    @Override
    public void put(String key, Path file) {
        try (var stream = Files.newInputStream(file)) {
            long size = Files.size(file);
            if (size < 1 || size > 64L * 1024 * 1024) throw new IllegalArgumentException("视频产物超过存储上限");
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(stream, size, 64L * 1024 * 1024)
                    .contentType("video/mp4")
                    .build()
            );
        } catch (Exception failure) {
            throw new ExternalDependencyUnavailableException("视频对象写入暂时不可用", null);
        }
    }

    @Override
    public InputStream open(String key, long offset, long length) {
        try {
            return client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).offset(offset).length(length).build()
            );
        } catch (Exception failure) {
            throw new ExternalDependencyUnavailableException("视频对象读取暂时不可用", null);
        }
    }

    /** HEAD 404不能证明版本桶已释放空间；先逐版本清理，再列举及HEAD双重核验。 */
    @Override
    public void removeAndConfirm(String key, long deadlineNanos) {
        try {
            if (
                key == null || !key.matches("videos/[1-9][0-9]*/[a-f0-9-]{36}/(?:source|[a-f0-9-]{36})\\.mp4")
            ) throw new IllegalArgumentException("非视频对象键");
            for (String version : versions(key, deadlineNanos)) {
                within(deadlineNanos);
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).versionId(version).build());
            }
            if (!versions(key, deadlineNanos).isEmpty()) throw new IllegalStateException("对象版本仍存在");
            within(deadlineNanos);
            try {
                client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
                throw new IllegalStateException("对象仍存在");
            } catch (ErrorResponseException absent) {
                if (
                    absent.response().code() != 404 ||
                    !List.of("NoSuchKey", "NoSuchObject").contains(absent.errorResponse().code())
                ) throw absent;
            }
        } catch (Exception failure) {
            // 不泄露对象路径、凭据、策略或供应商原文，也不把拒绝访问/超时当成已删除。
            throw new ExternalDependencyUnavailableException("视频对象清理尚未核验", null);
        }
    }

    private List<String> versions(String key, long deadlineNanos) throws Exception {
        within(deadlineNanos);
        var iterator = client
            .listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(key)
                    .recursive(true)
                    .includeVersions(true)
                    .maxKeys(16)
                    .build()
            )
            .iterator();
        var versions = new ArrayList<String>();
        int visited = 0;
        while (true) {
            within(deadlineNanos);
            if (!iterator.hasNext()) break;
            if (++visited > 16) throw new IllegalStateException("版本扫描超过上限");
            within(deadlineNanos);
            var item = iterator.next().get();
            // prefix不是等值，绝不能删除名称相近的其他对象。
            if (item.objectName().equals(key)) {
                if (versions.size() >= 8) throw new IllegalStateException("对象版本超过上限");
                versions.add(item.versionId() == null ? "null" : item.versionId());
            }
        }
        return versions;
    }

    private static void within(long deadlineNanos) throws java.util.concurrent.TimeoutException {
        if (
            Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos
        ) throw new java.util.concurrent.TimeoutException("Video cleanup deadline");
    }
}
