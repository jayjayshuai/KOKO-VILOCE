package cn.kokonexus.asset.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** asset-service：MinioAssetStorage 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class MinioAssetStorage implements AssetStorage {

    /** MinioClient 外部或领域适配器，失败不伪装为业务成功。 */
    private final MinioClient client;
    /** 专用对象存储桶名称。 */
    private final String bucket;

    public MinioAssetStorage(
        @Value("${koko.asset.endpoint}") String endpoint,
        @Value("${koko.asset.bucket}") String bucket,
        @Value("${koko.asset.access-key}") String accessKey,
        @Value("${koko.asset.secret-key}") String secretKey
    ) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("媒体资产 MinIO 专用凭据未配置");
        }
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        try {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                    .contentType(contentType)
                    .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("媒体对象存储写入失败", exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            byte[] bytes = stream.readNBytes(5 * 1024 * 1024 + 1);
            if (bytes.length > 5 * 1024 * 1024) throw new IllegalStateException("媒体对象超过读取上限");
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("媒体对象读取失败", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("媒体对象读取失败", exception);
        }
    }

    @Override
    public void remove(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IllegalStateException("媒体对象清理失败", exception);
        }
    }
}
