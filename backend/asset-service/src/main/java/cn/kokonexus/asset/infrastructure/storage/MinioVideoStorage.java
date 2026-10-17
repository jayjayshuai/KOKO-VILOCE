package cn.kokonexus.asset.infrastructure.storage;

import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
}
