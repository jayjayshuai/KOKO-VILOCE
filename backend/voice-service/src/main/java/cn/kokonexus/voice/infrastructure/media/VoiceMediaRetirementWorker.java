package cn.kokonexus.voice.infrastructure.media;

import cn.kokonexus.common.diagnostics.SafeFailureDetails;
import cn.kokonexus.voice.application.VoiceMediaRetirementState;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单批四个固定旧身份，进程恢复查库领取；不是逐参与者持续核验或旧JWT撤销系统。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "koko.voice.media-retirement", name = "enabled", havingValue = "true")
public class VoiceMediaRetirementWorker {

    /** 真实Spring事务代理，不能自调用绕过领取事务。 */ private final VoiceMediaRetirementState state;
    /** 实际媒体API，最多五秒/零隐式重试；不把业务账户当媒体身份。 */ private final VoiceMediaGateway media;

    @Scheduled(fixedDelayString = "${koko.voice.media-retirement.poll-ms:5000}")
    public void tick() {
        try {
            state.exhaustExpired();
            for (var job : state.claim(UUID.randomUUID().toString())) {
                try {
                    media.removeParticipant(job.getProviderRoomName(), job.getMediaIdentity());
                } catch (RuntimeException failure) {
                    log.warn("媒体退场未确认：jobId={}，failure={}", job.getId(), SafeFailureDetails.describe(failure));
                    if (!state.failed(job)) log.warn("媒体退场失败确认丢失租约：jobId={}", job.getId());
                    continue;
                }
                if (!state.confirmed(job.getId(), job.getLeaseToken())) log.warn(
                    "媒体退场成功确认丢失租约：jobId={}",
                    job.getId()
                );
            }
        } catch (RuntimeException failure) {
            log.error("媒体退场轮询失败：failure={}", SafeFailureDetails.describe(failure));
        }
    }
}
