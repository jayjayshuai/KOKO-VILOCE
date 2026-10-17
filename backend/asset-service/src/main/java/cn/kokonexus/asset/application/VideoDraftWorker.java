package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描只投递到单线程零队列执行器；跨JVM处理上限由SQL领取及租约控制。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.video", name = "enabled", havingValue = "true")
public class VideoDraftWorker {

    /** 仅记录草稿UUID及错误类别，不写命令参数或供应商输出。 */ private static final Logger log =
        LoggerFactory.getLogger(VideoDraftWorker.class);
    /** 短事务状态用例。 */ private final VideoDraftState state;
    /** 有界私有存储。 */ private final VideoStorage storage;
    /** 真正解码/转码适配器。 */ private final VideoProcessor processor;
    /** 本机私有工作目录。 */ private final VideoFiles files;
    /** 零等待队列避免定时触发积压；独立线程不阻塞其他资产定时任务。 */ private final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                var thread = new Thread(r, "video-draft-worker");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

    @Scheduled(fixedDelayString = "${koko.video.scan-delay-ms:2000}")
    public void tick() {
        try {
            executor.execute(this::work);
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            /* 正在处理或已关闭，不排队重复扫描。 */
        }
    }

    /** 可单独集成验证；始终通过外部状态代理领取，不在调度器内自调用事务方法。 */
    public void work() {
        VideoDraft row = null;
        Path directory = null;
        try {
            row = state.claim();
            if (row == null) return;
            directory = files.create();
            Path source = directory.resolve("source.mp4"),
                output = directory.resolve("output.mp4");
            var digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (
                var in = storage.open(VideoDraftService.sourceKey(row), 0, row.getSourceBytes());
                var out = Files.newOutputStream(source)
            ) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    count += read;
                    if (count > row.getSourceBytes()) throw new VideoProcessor.InvalidVideoException();
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (
                count != row.getSourceBytes() ||
                !HexFormat.of().formatHex(digest.digest()).equals(row.getSourceSha256())
            ) throw new VideoProcessor.InvalidVideoException();
            if (!state.canProcess(row)) throw new java.util.concurrent.TimeoutException(
                "Video processing lease budget"
            );
            var result = processor.process(source, output);
            storage.put(VideoDraftService.outputKey(row), output);
            row.setOutputBytes(Files.size(output));
            row.setDurationMillis(result.durationMillis());
            row.setWidth(result.width());
            row.setHeight(result.height());
            if (!state.ready(row)) log.warn("Video completion fenced id={}", row.getId());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (row != null) {
                String code =
                    failure instanceof VideoProcessor.InvalidVideoException
                        ? "INVALID_VIDEO"
                        : failure instanceof ExternalDependencyUnavailableException
                          ? "STORAGE_UNAVAILABLE"
                          : failure instanceof InterruptedException
                            ? "INTERRUPTED"
                            : "PROCESSING_FAILED";
                try {
                    state.failed(row, code);
                } catch (RuntimeException unconfirmed) {
                    log.warn("Video failure state unconfirmed id={}", row.getId());
                }
                log.warn("Video processing failed id={} type={}", row.getId(), failure.getClass().getSimpleName());
            } else log.warn("Video scan unavailable type={}", failure.getClass().getSimpleName());
        } finally {
            if (directory != null) try {
                files.cleanup(directory);
            } catch (Exception failure) {
                log.warn("Video task cleanup unconfirmed");
            }
        }
    }

    @PreDestroy
    public void close() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
