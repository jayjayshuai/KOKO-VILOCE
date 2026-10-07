package cn.kokonexus.gateway.filter;

import cn.kokonexus.gateway.infrastructure.*;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.*;

/** 媒体WS持续网站/房间/绑定核验；仅信令关闭，不伪称直接撤销已建立RTP音轨。 */
@Component
public class MediaWebSocketLease implements AutoCloseable {

    /** 同步Redis/Triple独立有界池，不在parallel或Netty线程阻塞。 */ private final Scheduler workers;
    /** 单实例在监控的连接上限；跨实例配额仍需容量/共享租约验收。 */ private final Semaphore slots;
    /** 真实语音当前准入RPC，失败不允许继续。 */ private final MediaAdmissionClient media;
    /** 当前共享网站会话，不读取浏览器脚本存储。 */ private final MediaWebsiteSessionCheck website;
    /** 固定核验周期毫秒，可测试缩短，正式默认一秒。 */ private final Duration interval;

    public MediaWebSocketLease(
        MediaAdmissionClient media,
        MediaWebsiteSessionCheck website,
        @Value("${koko.gateway.media-admission.lease-interval-ms:1000}") long intervalMs,
        @Value("${koko.gateway.media-admission.max-connections:128}") int connections,
        @Value("${koko.gateway.media-admission.lease-workers:2}") int threads,
        @Value("${koko.gateway.media-admission.lease-queued-tasks-per-thread:32}") int queue
    ) {
        if (
            intervalMs < 20 ||
            intervalMs > 5000 ||
            connections < 1 ||
            connections > 512 ||
            threads < 1 ||
            threads > 16 ||
            queue < 1 ||
            queue > 128
        ) throw new IllegalArgumentException("媒体连接核验资源超界");
        this.media = media;
        this.website = website;
        this.interval = Duration.ofMillis(intervalMs);
        this.slots = new Semaphore(connections);
        this.workers = Schedulers.newBoundedElastic(threads, queue, "koko-media-lease", 60, true);
    }

    /** 每连接一次监控，结束/取消归还槽位；并行核验不允许旧回复重新放开已关闭连接。 */
    public Mono<Void> handle(WebSocketSession session, WebSocketHandler application, MediaConnectionProof proof) {
        return Mono.defer(() -> {
            if (proof == null) return session.close(new CloseStatus(1008, "MEDIA_PROOF_REQUIRED"));
            if (!slots.tryAcquire()) return session.close(new CloseStatus(1013, "MEDIA_LEASE_BUSY"));
            var ended = Sinks.<Boolean>one();
            var denied = Mono.fromCallable(
                () ->
                    website.active(proof.websiteToken(), proof.admission().userId()) && media.retain(proof.admission())
            )
                .subscribeOn(workers)
                .timeout(Duration.ofSeconds(3))
                .onErrorReturn(false)
                .delaySubscription(interval)
                .repeat()
                .filter(allowed -> !allowed)
                .next()
                .takeUntilOther(ended.asMono())
                .flatMap(ignored -> session.close(new CloseStatus(1008, "MEDIA_LEASE_ENDED")));
            // takeUntil放在close前：代理结束只取消尚未决策的轮询，不能取消已开始的关闭帧写入。
            var forwarding = Mono.defer(() -> application.handle(session)).doFinally(signal ->
                ended.tryEmitValue(true)
            );
            return Mono.when(forwarding, denied).doFinally(signal -> slots.release());
        });
    }

    @Override
    public void close() {
        workers.dispose();
    }
}
