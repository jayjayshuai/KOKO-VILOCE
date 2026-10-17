package cn.kokonexus.asset.application;

import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.persistence.VideoDraftMapper;
import cn.kokonexus.common.api.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
        var row = mapper.selectById(id);
        if (row == null || row.getOwnerId() != owner) throw new ResourceNotFoundException("视频草稿不存在");
        return row;
    }

    public List<VideoDraft> owned(long owner) {
        if (owner <= 0) throw new IllegalArgumentException("用户标识无效");
        return mapper.owned(owner);
    }

    public static void canonical(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(
            "视频UUID无效"
        );
    }

    private static void one(int rows, String message) {
        if (rows != 1) throw new IllegalStateException(message);
    }
}
