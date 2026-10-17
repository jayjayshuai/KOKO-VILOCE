package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 原上传意图先持久化，再写私有对象，最后排队；重试仅恢复同一不可变请求。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.video", name = "enabled", havingValue = "true")
public class VideoDraftService {

    /** 原请求和额度的短事务代理。 */ private final VideoDraftState state;
    /** 流式私有对象存储。 */ private final VideoStorage storage;
    /** 服务器私有临时任务路径。 */ private final VideoFiles files;
    /** 单实例最多一个32MiB上传落盘/存储调用，不为每个请求分配大byte数组。 */ private final Semaphore uploads =
        new Semaphore(1);

    public VideoDraft upload(long owner, String request, String title, String description, MultipartFile file)
        throws Exception {
        if (owner <= 0) throw new IllegalArgumentException("用户标识无效");
        VideoDraftState.canonical(request);
        title = title == null ? "" : title.strip();
        description = description == null ? "" : description.strip();
        if (title.isBlank() || title.length() > 100 || description.length() > 1000) throw new IllegalArgumentException(
            "标题需1～100字，说明最多1000字"
        );
        if (
            file == null || file.getSize() < 16 || file.getSize() > 32L * 1024 * 1024
        ) throw new IllegalArgumentException("视频需为16字节至32MiB的MP4文件");
        if (!uploads.tryAcquire()) throw new AssetUploadBusyException();
        Path directory = null;
        try {
            directory = files.create();
            Path source = directory.resolve("source.mp4");
            var digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (var in = file.getInputStream(); var out = Files.newOutputStream(source)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    count += read;
                    if (count > 32L * 1024 * 1024) throw new IllegalArgumentException("视频超过32MiB");
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            byte[] signature;
            try (var in = Files.newInputStream(source)) {
                signature = in.readNBytes(16);
            }
            if (
                count != file.getSize() ||
                signature.length < 16 ||
                !new String(signature, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")
            ) throw new IllegalArgumentException("请选择完整MP4视频，扩展名不能代替真实格式");
            var input = new VideoDraft();
            input.setId(UUID.randomUUID().toString());
            input.setOwnerId(owner);
            input.setRequestId(request);
            input.setTitle(title);
            input.setDescription(description);
            input.setSourceBytes(count);
            input.setSourceSha256(HexFormat.of().formatHex(digest.digest()));
            VideoDraft row = state.register(input);
            if (row.getState().equals("UPLOADING")) {
                var ticket = state.beginSource(owner, row.getId());
                try {
                    storage.put(sourceKey(row), source);
                } catch (RuntimeException failure) {
                    try {
                        state.writeUncertain(ticket);
                    } catch (RuntimeException unconfirmed) {
                        failure.addSuppressed(unconfirmed);
                    }
                    throw failure;
                }
                return state.sourceStored(owner, ticket);
            }
            return row;
        } finally {
            try {
                if (directory != null) files.cleanup(directory);
            } finally {
                uploads.release();
            }
        }
    }

    public static String sourceKey(VideoDraft row) {
        return "videos/" + row.getOwnerId() + "/" + row.getId() + "/source.mp4";
    }

    public static String outputKey(VideoDraft row) {
        return "videos/" + row.getOwnerId() + "/" + row.getId() + "/" + row.getRunId() + ".mp4";
    }
}
