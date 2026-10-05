package cn.kokonexus.live.application;

import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.live.domain.LiveStream;
import cn.kokonexus.live.infrastructure.persistence.LiveStreamMapper;
import cn.kokonexus.outbox.OutboxWriter;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Locale;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** live-service：业务用例；涉及写入时遵守领域事务与权限约束。 */
@Service
public class LiveApplicationService {

    /** LiveStreamMapper 持久化映射器，复杂 SQL 使用 XML。 */
    private final LiveStreamMapper liveStreamMapper;
    /** OutboxWriter 外部或领域适配器，失败不伪装为业务成功。 */
    private final OutboxWriter outboxWriter;

    /** IdentityRpcService 跨服务契约代理，不直接读取其他服务数据库。 */
    @DubboReference(version = "1.0.0", check = false, timeout = 3000, retries = 0)
    private IdentityRpcService identityRpcService;

    public LiveApplicationService(LiveStreamMapper liveStreamMapper, OutboxWriter outboxWriter) {
        this.liveStreamMapper = liveStreamMapper;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public LiveStream create(long creatorId, String slug, String title, String category, boolean interactive) {
        var identity = identityRpcService.findActiveUser(String.valueOf(creatorId));
        LiveStream stream = new LiveStream();
        stream.setCreatorId(creatorId);
        stream.setCreatorName(identity.displayName());
        stream.setSlug(slug.toLowerCase(Locale.ROOT));
        stream.setTitle(title.trim());
        stream.setCategory(category.trim());
        stream.setStatus("SCHEDULED");
        stream.setProvider("UNCONFIGURED");
        stream.setInteractive(interactive);
        stream.setViewerCount(0L);
        stream.setVersion(0);
        try {
            if (liveStreamMapper.insert(stream) != 1) {
                throw new IllegalStateException("直播创建失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("直播地址已被占用", exception);
        }
        return stream;
    }

    @Transactional
    public void transition(long creatorId, long streamId, String targetStatus) {
        LiveStream stream = null;
        if ("LIVE".equals(targetStatus)) {
            stream = liveStreamMapper.selectById(streamId);
            if (stream == null || !creatorIdEquals(stream, creatorId)) {
                throw new IllegalArgumentException("直播不存在或无权操作");
            }
            if ("UNCONFIGURED".equals(stream.getProvider()) || stream.getProviderInputId() == null) {
                throw new IllegalStateException("尚未配置直播媒体供应商，不能开始推流");
            }
        }
        String expectedStatus = switch (targetStatus) {
            case "LIVE" -> "SCHEDULED";
            case "ENDED" -> "LIVE";
            default -> throw new IllegalArgumentException("不支持的直播状态");
        };
        if (liveStreamMapper.transitionStatus(streamId, creatorId, expectedStatus, targetStatus) != 1) {
            throw new IllegalArgumentException("直播不存在、无权操作或当前状态不允许此操作");
        }
        if ("LIVE".equals(targetStatus)) {
            outboxWriter.enqueueLiveStarted(creatorId, streamId, stream.getTitle());
        }
    }

    private boolean creatorIdEquals(LiveStream stream, long creatorId) {
        return stream.getCreatorId() != null && stream.getCreatorId() == creatorId;
    }

    @Transactional(readOnly = true)
    public List<LiveStream> discover(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        Page<LiveStream> page = Page.of(1, safeLimit, false);
        page.addOrder(OrderItem.desc("started_at"));
        IPage<LiveStream> result = liveStreamMapper.selectPage(
            page,
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<LiveStream>lambdaQuery().eq(LiveStream::getStatus, "LIVE")
        );
        return result.getRecords();
    }
}
