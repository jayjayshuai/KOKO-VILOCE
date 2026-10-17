package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.persistence.VideoDraftMapper;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import cn.kokonexus.common.api.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只执行短SQL事务；文件、网络和FFmpeg不进入此代理持锁区。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "koko.video", name = "enabled", havingValue = "true")
public class VideoDraftState {

    /** 原请求、固定预算及领取事实。 */ private final VideoDraftMapper mapper;

    /** 同用户同请求同字节及资料只预留一次；全局最多10条、本人最多5条，每条预留96MiB。 */
    @Transactional(timeout = 5)
    public VideoDraft register(VideoDraft input) {
        if (
            input == null ||
            input.getOwnerId() == null ||
            input.getOwnerId() <= 0 ||
            input.getSourceBytes() == null ||
            input.getSourceBytes() < 16 ||
            input.getSourceBytes() > 33554432 ||
            input.getSourceSha256() == null ||
            !input.getSourceSha256().matches("[a-f0-9]{64}") ||
            input.getTitle() == null ||
            input.getTitle().isBlank() ||
            input.getTitle().length() > 100 ||
            input.getDescription() == null ||
            input.getDescription().length() > 1000
        ) throw new IllegalArgumentException("视频登记参数无效");
        canonical(input.getId());
        canonical(input.getRequestId());
        mapper.lockGlobal();
        var old = mapper.original(input.getOwnerId(), input.getRequestId());
        if (old != null) {
            if (
                !old.getSourceSha256().equals(input.getSourceSha256()) ||
                !old.getSourceBytes().equals(input.getSourceBytes()) ||
                !old.getTitle().equals(input.getTitle()) ||
                !old.getDescription().equals(input.getDescription())
            ) throw new IllegalStateException("原上传请求不能更换文件或资料");
            return old;
        }
        one(mapper.reserve(0, 10), "视频处理总额度已满，请稍后联系运营");
        mapper.ensureCapacity(input.getOwnerId());
        one(mapper.reserve(input.getOwnerId(), 5), "本人视频草稿额度已满（含失败及未清理上传）");
        one(mapper.insertDraft(input), "视频登记未确认");
        return mapper.selectById(input.getId());
    }

    /** 存储返回成功后才进入队列；并发原请求不能把READY/FAILED重新排队。 */
    @Transactional(timeout = 5)
    public VideoDraft queued(long owner, String id) {
        owned(owner, id);
        mapper.queue(id);
        return owned(owner, id);
    }

    /** 过期任务只进入失败，不自动反复消耗转码资源；领取轮次隔离旧产物。 */
    @Transactional(timeout = 5)
    public VideoDraft claim() {
        mapper.lockGlobal();
        mapper.expire();
        if (mapper.processingCount() != 0) return null;
        var row = mapper.next();
        if (row == null) return null;
        String run = UUID.randomUUID().toString();
        one(mapper.claim(row.getId(), run), "视频领取未确认");
        return mapper.selectById(row.getId());
    }

    /** 只有未过期的原领取轮次可确认成功；过期完成不冒充可播放。 */
    @Transactional(timeout = 5)
    public boolean ready(VideoDraft row) {
        return mapper.ready(row) == 1;
    }

    /** 下载过慢时不再启动可能超出原租约的处理进程。 */
    public boolean canProcess(VideoDraft row) {
        return mapper.canProcess(row.getId(), row.getRunId());
    }

    /** 错误只保存固定枚举，不把命令行、对象键和进程原始输出暴露到页面。 */
    @Transactional(timeout = 5)
    public void failed(VideoDraft row, String code) {
        if (
            !List.of("INVALID_VIDEO", "PROCESSING_FAILED", "STORAGE_UNAVAILABLE", "INTERRUPTED").contains(code)
        ) throw new IllegalArgumentException("视频失败代码无效");
        mapper.fail(row.getId(), row.getRunId(), code);
    }

    public VideoDraft owned(long owner, String id) {
        if (owner <= 0) throw new IllegalArgumentException("用户标识无效");
        canonical(id);
        var row = mapper.ownedOne(owner, id);
        if (row == null || row.getOwnerId() != owner) throw new ResourceNotFoundException("视频草稿不存在");
        return row;
    }

    public List<VideoDraft> owned(long owner) {
        if (owner <= 0) throw new IllegalArgumentException("用户标识无效");
        return mapper.owned(owner);
    }

    /** PUT前持久登记唯一写入；在途或未知的原写入不能靠新PUT冒充恢复。 */
    @Transactional(timeout = 5)
    public WriteTicket beginSource(long owner, String id) {
        mapper.lockGlobal();
        owned(owner, id);
        var row = mapper.lockVideo(id);
        if (
            !row.getState().equals("UPLOADING") || !Boolean.TRUE.equals(row.getWriteTracking())
        ) throw new IllegalStateException("视频已变化或旧版写入待核验，请刷新状态");
        return begin(row, "SOURCE", null);
    }

    @Transactional(timeout = 5)
    public WriteTicket beginOutput(VideoDraft job) {
        mapper.lockGlobal();
        var row = mapper.lockVideo(job.getId());
        if (
            row == null ||
            !Boolean.TRUE.equals(row.getWriteTracking()) ||
            !mapper.outputAllowed(row.getId(), job.getRunId())
        ) throw new IllegalStateException("视频输出权限已失效");
        return begin(row, "OUTPUT", job.getRunId());
    }

    private WriteTicket begin(VideoDraft row, String kind, String run) {
        String id = UUID.randomUUID().toString();
        try {
            one(mapper.beginWrite(id, row.getId(), kind, run), "视频写入登记失败");
        } catch (DuplicateKeyException duplicate) {
            throw new ExternalDependencyUnavailableException("原对象写入仍待核验，不会重复写入", null);
        }
        return new WriteTicket(row.getId(), id, kind, run);
    }

    /** 仅在PUT返回成功后调用；删除已受理时只结清写入，不重新排队。 */
    @Transactional(timeout = 5)
    public VideoDraft sourceStored(long owner, WriteTicket ticket) {
        if (!"SOURCE".equals(ticket.kind()) || ticket.runId() != null) throw new IllegalArgumentException(
            "非原文件写入回执"
        );
        mapper.lockGlobal();
        owned(owner, ticket.videoId());
        mapper.lockVideo(ticket.videoId());
        settle(ticket);
        mapper.queue(ticket.videoId());
        return owned(owner, ticket.videoId());
    }

    /** 输出回执和READY条件同事务；删除或过期后不能恢复预览。 */
    @Transactional(timeout = 5)
    public boolean outputStored(VideoDraft job, WriteTicket ticket) {
        if (!"OUTPUT".equals(ticket.kind())) throw new IllegalArgumentException("非输出写入回执");
        mapper.lockGlobal();
        mapper.lockVideo(job.getId());
        if (
            !job.getId().equals(ticket.videoId()) || !job.getRunId().equals(ticket.runId())
        ) throw new IllegalArgumentException("原输出写入不匹配");
        settle(ticket);
        return mapper.ready(job) == 1;
    }

    private void settle(WriteTicket ticket) {
        if (
            mapper.settleWrite(ticket.id(), ticket.videoId()) != 1 &&
            !mapper.settledWrite(ticket.id(), ticket.videoId())
        ) throw new IllegalStateException("原写入结果尚未确认");
    }

    @Transactional(timeout = 5)
    public void writeUncertain(WriteTicket ticket) {
        mapper.lockGlobal();
        mapper.uncertainWrite(ticket.id(), ticket.videoId());
    }

    @Transactional(timeout = 5)
    public void processed(VideoDraft job) {
        mapper.lockGlobal();
        mapper.processed(job.getId(), job.getRunId());
    }

    /** 内部写入围栏，不向HTTP返回操作令牌或对象键。 */
    public record WriteTicket(
        /** 原视频UUID。 */ String videoId,
        /** 唯一PUT登记UUID。 */ String id,
        /** SOURCE/OUTPUT。 */ String kind,
        /** 输出领取轮次，可空。 */ String runId
    ) {}

    public static void canonical(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(
            "视频UUID无效"
        );
    }

    private static void one(int rows, String message) {
        if (rows != 1) throw new IllegalStateException(message);
    }
}
