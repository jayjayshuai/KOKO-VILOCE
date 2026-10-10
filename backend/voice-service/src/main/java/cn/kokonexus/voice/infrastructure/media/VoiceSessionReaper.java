package cn.kokonexus.voice.infrastructure.media;

import cn.kokonexus.common.diagnostics.SafeFailureDetails;
import cn.kokonexus.voice.application.VoiceInteractionService;
import cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 无页面请求时仍回收过期成员并提交旧身份退场；不在SQL事务中访问SFU。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "koko.voice.session-reaper", name = "enabled", havingValue = "true")
public class VoiceSessionReaper {

    /** 只发现过期房间，不以列表快照作为写入依据。 */ private final VoiceMediaPlanMapper mapper;
    /** 当前房间锁/回收/审计/媒体计划的Spring事务代理。 */ private final VoiceInteractionService core;
    /** 单轮最多1～16个房间，避免共享数据库被无界循环占用。 */ private final int batch;
    /** 同实例定时任务不重入；跨实例正确性由房间行锁与同事务状态提供。 */ private final AtomicBoolean running =
        new AtomicBoolean();
    /** 非授权状态的扫描位置；进程重启从零开始，不把内存游标作为分布式锁。 */ private long cursor;

    public VoiceSessionReaper(
        VoiceMediaPlanMapper mapper,
        VoiceInteractionService core,
        @Value("${koko.voice.interaction-core-enabled:false}") boolean coreEnabled,
        @Value("${koko.voice.media-plan-enabled:false}") boolean planEnabled,
        @Value("${koko.voice.media-retirement.enabled:false}") boolean retirementEnabled,
        @Value("${koko.voice.session-reaper.batch-size:4}") int batch,
        @Value("${koko.voice.session-reaper.poll-ms:5000}") long pollMs
    ) {
        if (!coreEnabled || !planEnabled || !retirementEnabled) throw new IllegalArgumentException(
            "成员回收需同时启用核心、媒体计划与退场执行"
        );
        if (batch < 1 || batch > 16 || pollMs < 1000 || pollMs > 60000) throw new IllegalArgumentException(
            "成员回收批量/周期超界"
        );
        this.mapper = mapper;
        this.core = core;
        this.batch = batch;
    }

    /** 发现失败不改扫描位置；单房失败仍推进游标，下一圈再核实，不能阻断其他房间。 */
    @Scheduled(fixedDelayString = "${koko.voice.session-reaper.poll-ms:5000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var rooms = mapper.expiredMemberRooms(cursor, batch);
            if (rooms == null || rooms.size() > batch) throw new IllegalStateException("回收发现结果异常");
            if (rooms.isEmpty()) {
                cursor = 0;
                return;
            }
            for (Long room : rooms) {
                if (room == null || room <= cursor) throw new IllegalStateException("回收游标次序异常");
                cursor = room;
                try {
                    core.reapExpiredMembers(room);
                } catch (RuntimeException failure) {
                    log.warn("过期房间回收未确认：roomId={}，failure={}", room, SafeFailureDetails.describe(failure));
                }
            }
        } catch (RuntimeException failure) {
            log.error("过期成员发现失败：failure={}", SafeFailureDetails.describe(failure));
        } finally {
            running.set(false);
        }
    }
}
