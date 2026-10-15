package cn.kokonexus.chat.application;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 原生通话独立单线程有界对账，不占Netty事件线程或聊天同步工作线程。 */
@Component
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallReconciler {

    /** 不记录私人通话标识、参与者或网站摘要。 */
    private static final Logger LOG = LoggerFactory.getLogger(NativeCallReconciler.class);
    /** Spring事务代理用例，每个通话独立事务。 */
    private final NativeCallService calls;
    /** 单线程控制扫描游标，生命周期随本服务结束。 */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "native-call-reconcile");
        t.setDaemon(true);
        return t;
    });
    /** 仅影响扫描顺序，不承担权限或持久状态。 */
    private String after;
    /** 重复应用就绪事件不重复启动扫描。 */
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();

    public NativeCallReconciler(NativeCallService calls) {
        this.calls = calls;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (started.compareAndSet(false, true)) executor.scheduleWithFixedDelay(this::tick, 2, 2, TimeUnit.SECONDS);
    }

    private void tick() {
        try {
            var ids = calls.scan(after);
            if (ids.isEmpty()) {
                after = null;
                return;
            }
            for (String id : ids) {
                try {
                    calls.reconcile(id);
                } catch (RuntimeException failure) {
                    LOG.warn("Native call reconciliation failed; details withheld");
                }
                after = id;
            }
        } catch (RuntimeException failure) {
            LOG.warn("Native call scan unavailable; details withheld");
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(6, TimeUnit.SECONDS)) LOG.warn(
                "Native call reconciliation termination unconfirmed"
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
