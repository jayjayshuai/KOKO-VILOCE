package cn.kokonexus.voice.application;

import cn.kokonexus.voice.domain.VoiceMediaPlan.Retirement;
import cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 领取/确认各自短事务；LiveKit调用在事务外，旧租约不能写新任务结果。 */
@Service
@RequiredArgsConstructor
public class VoiceMediaRetirementState {

    /** 带状态/租约/数据库时刻的CAS。 */ private final VoiceMediaPlanMapper mapper;

    /** 清理耗尽租约与领取分开提交，避免空PROCESSING索引间隙与后续领取互相锁住。 */
    @Transactional(timeout = 3)
    public int exhaustExpired() {
        return mapper.exhaustExpired();
    }

    @Transactional(timeout = 3, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<Retirement> claim(String token) {
        uuid(token);
        var jobs = new java.util.ArrayList<>(mapper.dueExpired(4));
        if (jobs.size() < 4) jobs.addAll(mapper.duePending(4 - jobs.size()));
        for (var job : jobs) {
            if (mapper.claim(job.getId(), token) != 1) throw new IllegalStateException("媒体退场领取未确认");
            job.setAttempts(job.getAttempts() + 1);
            job.setLeaseToken(token);
            job.setJobState("PROCESSING");
        }
        return List.copyOf(jobs);
    }

    /** SQL成功确认失败时保留租约，下一轮可安全重删原身份，不伪装SFU失败。 */
    @Transactional(timeout = 3)
    public boolean confirmed(String id, String token) {
        uuid(id);
        uuid(token);
        return mapper.confirmed(id, token) == 1;
    }

    /** 失败确认在实际拥有且未过期租约下进行，十次耗尽不得自动重新领取。 */
    @Transactional(timeout = 3)
    public boolean failed(Retirement job) {
        uuid(job.getId());
        uuid(job.getLeaseToken());
        int attempts = job.getAttempts();
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("媒体退场次数无效");
        return mapper.failed(job.getId(), job.getLeaseToken(), attempts >= 10, Math.min(1 << attempts, 300)) == 1;
    }

    private static void uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(
            "退场任务/租约无效"
        );
    }
}
