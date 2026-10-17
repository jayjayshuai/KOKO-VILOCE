package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 对象IO不持数据库锁；跨实例清理租约及有限重试，不在请求线程阻塞删除。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.video", name = { "enabled", "deletion-enabled" }, havingValue = "true")
public class VideoDeletionWorker {

    /** 只记草稿UUID与故障类别。 */ private static final Logger log = LoggerFactory.getLogger(
        VideoDeletionWorker.class
    );
    /** 代理事务状态。 */ private final VideoDeletionState state;
    /** 视频前缀专用存储。 */ private final VideoStorage storage;
    /** 本实例单线程且零任务队列，扫描不积压。 */ private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        r -> {
            var t = new Thread(r, "video-deletion-worker");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.AbortPolicy()
    );

    @Scheduled(fixedDelayString = "${koko.video.delete-scan-delay-ms:2500}")
    public void tick() {
        try {
            executor.execute(this::work);
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            /* 清理忙或关闭，不排队。 */
        }
    }

    public void work() {
        VideoDraft job = null;
        try {
            job = state.claim();
            if (job == null) return;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            storage.removeAndConfirm(VideoDraftService.sourceKey(job), deadline);
            if (job.getRunId() != null) storage.removeAndConfirm(VideoDraftService.outputKey(job), deadline);
            if (!state.complete(job)) log.warn("Video cleanup completion fenced id={}", job.getId());
        } catch (RuntimeException failure) {
            if (job != null) {
                try {
                    state.failed(job);
                } catch (RuntimeException unconfirmed) {
                    log.warn("Video cleanup retry state unconfirmed id={}", job.getId());
                }
            }
            log.warn(
                "Video cleanup unavailable id={} type={}",
                job == null ? null : job.getId(),
                failure.getClass().getSimpleName()
            );
        }
    }

    @PreDestroy
    public void close() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
