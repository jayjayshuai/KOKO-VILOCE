package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.persistence.VideoDraftMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 删除意图/领取/额度回收的短事务；对象存储核验必须在事务外完成。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.video", name = { "enabled", "deletion-enabled" }, havingValue = "true")
public class VideoDeletionState {

    /** 全部写入及清理统一先取全局锁，避免交叉锁序。 */ private final VideoDraftMapper mapper;
    /** 本人所有权与当前投影查询。 */ private final VideoDraftState videos;

    @Transactional(timeout = 5)
    public VideoDraft request(long owner, String id, String request) {
        VideoDraftState.canonical(request);
        mapper.lockGlobal();
        videos.owned(owner, id);
        var row = mapper.lockVideo(id);
        if (row.getDeleteRequestId() != null) {
            if (!row.getDeleteRequestId().equals(request)) throw new IllegalStateException(
                "视频已有删除请求，请刷新核对原结果"
            );
            return videos.owned(owner, id);
        }
        one(mapper.requestDelete(id, request));
        return videos.owned(owner, id);
    }

    @Transactional(timeout = 5)
    public VideoDraft claim() {
        mapper.lockGlobal();
        if (mapper.cleanupBusy() != 0) return null;
        var row = mapper.nextDeletion();
        if (row == null) return null;
        one(mapper.claimDeletion(row.getId(), UUID.randomUUID().toString()));
        return mapper.lockVideo(row.getId());
    }

    /** 仅被清理执行器在所有已知版本核验不存在后调用；同事务回收两份额度。 */
    @Transactional(timeout = 5)
    public boolean complete(VideoDraft job) {
        mapper.lockGlobal();
        var row = mapper.lockVideo(job.getId());
        if (
            row == null ||
            !row.getState().equals("DELETING") ||
            !Boolean.TRUE.equals(row.getWriteTracking()) ||
            mapper.unresolvedWrites(row.getId()) != 0
        ) return false;
        if (mapper.deletionDone(row.getId(), job.getDeleteToken()) != 1) return false;
        one(mapper.release(0));
        one(mapper.release(row.getOwnerId()));
        return true;
    }

    @Transactional(timeout = 5)
    public boolean failed(VideoDraft job) {
        mapper.lockGlobal();
        return mapper.deletionFailed(job.getId(), job.getDeleteToken()) == 1;
    }

    private static void one(int rows) {
        if (rows != 1) throw new IllegalStateException("视频删除或额度变更未确认");
    }
}
