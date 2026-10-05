package cn.kokonexus.outbox.binding;

import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 正向结束证明与释放任务同库事务；只有新协议OPEN且取得同行锁才能封存。 */
@RequiredArgsConstructor
public class BindingAttemptResolution {

    /** 与实际业务写入同一凭据表。 */
    private final BindingAttemptMapper mapper;
    /** 独立代理强制在本事务登记原requestId任务。 */
    private final BindingReleaseWriter writer;

    /** 已知业务回滚后调用；COMMITTED/ABORTED重复通知不改事实，不对缺行造证明。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean abort(String requestId) {
        var row = mapper.lock(requestId);
        if (row == null || !"OPEN".equals(row.getStatus())) return false;
        finalizeOpen(row);
        return true;
    }

    /** 行锁而非五分钟年龄判定结束；活跃写入被SKIP LOCKED排除，全批无外部I/O。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public int reconcile() {
        var rows = mapper.idleOpen();
        if (rows.size() > 8) throw new IllegalStateException("绑定核对批次越界");
        for (var row : rows) {
            if (!"OPEN".equals(row.getStatus())) throw new IllegalStateException("绑定核对状态异常");
            finalizeOpen(row);
        }
        return rows.size();
    }

    private void finalizeOpen(BindingAttempt row) {
        writer.stage(row.getOwnerId(), row.getAssetId(), row.getPurpose(), row.getRequestId());
        if (mapper.finish(row, "ABORTED") != 1) throw new IllegalStateException("绑定核对结束围栏失效");
    }
}
