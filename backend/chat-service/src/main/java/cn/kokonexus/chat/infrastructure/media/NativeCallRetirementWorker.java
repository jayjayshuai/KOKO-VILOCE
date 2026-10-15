package cn.kokonexus.chat.infrastructure.media;

import cn.kokonexus.chat.application.NativeCallRetirementState;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 私人清退独立单线程，每轮最多一个4秒网络请求，不占聊天Netty或公共语音线程。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallRetirementWorker implements AutoCloseable {

    /** Spring事务代理，与网络调用明确分开。 */ private final NativeCallRetirementState state;
    /** 真实SFU管理适配器，只接受固定通话UUID。 */ private final NativeCallMediaGateway media;
    /** 多次Ready事件不能创建多个轮询器。 */ private final AtomicBoolean started = new AtomicBoolean();
    /** 独占有界线程，关闭时等待正在执行的有限网络请求退出。 */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "native-call-retirement");
        thread.setDaemon(true);
        return thread;
    });

    public NativeCallRetirementWorker(NativeCallRetirementState state, NativeCallMediaGateway media) {
        this.state = state;
        this.media = media;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (started.compareAndSet(false, true)) executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public void tick() {
        try {
            var job = state.claim();
            if (job == null) return;
            try {
                if ("ROOM".equals(job.getAction())) media.delete(job.getCallId());
                else if ("PARTICIPANT".equals(job.getAction())) media.remove(job.getCallId(), job.getMediaIdentity());
                else throw new IllegalStateException("私人清退动作无效");
            } catch (RuntimeException unavailable) {
                if (!state.failed(job)) log.warn("Native call retirement failure lost its lease; details withheld");
                else log.warn("Native call retirement not confirmed; details withheld");
                return;
            }
            if (!state.confirmed(job)) log.warn("Native call retirement success lost its lease; details withheld");
        } catch (RuntimeException unavailable) {
            log.warn("Native call retirement poll unavailable; details withheld");
        }
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(6, TimeUnit.SECONDS)) log.warn(
                "Native call retirement termination unconfirmed"
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
