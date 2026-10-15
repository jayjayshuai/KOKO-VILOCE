package cn.kokonexus.chat.application;

import cn.kokonexus.chat.domain.NativeCallRetirement;
import cn.kokonexus.chat.persistence.NativeCallRetirementMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 清退领取和确认使用独立短事务，旧进程的迟到成功不得覆盖新租约。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.chat", name = "calls-enabled", havingValue = "true")
public class NativeCallRetirementState {

    /** 同库持久清退任务，不能用本机队列代替。 */ private final NativeCallRetirementMapper mapper;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 3)
    public NativeCallRetirement claim() {
        var job = mapper.due();
        if (job == null) return null;
        String lease = UUID.randomUUID().toString();
        if (mapper.claim(job.getJobKey(), lease) != 1) throw new IllegalStateException("私人媒体清退领取未确认");
        job.setLeaseToken(lease);
        return job;
    }

    @Transactional(timeout = 3)
    public boolean confirmed(NativeCallRetirement job) {
        return mapper.confirmed(job.getJobKey(), job.getLeaseToken()) == 1;
    }

    @Transactional(timeout = 3)
    public boolean failed(NativeCallRetirement job) {
        return mapper.failed(job.getJobKey(), job.getLeaseToken()) == 1;
    }
}
