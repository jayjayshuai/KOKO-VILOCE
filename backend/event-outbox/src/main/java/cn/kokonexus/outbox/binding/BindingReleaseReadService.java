package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.BindingReleaseView;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.outbox.operations.OutboxOperationsAuthorizer;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 每次在领域侧重新校验专用读权限；只读事务不改变释放任务和资产保护。 */
@RequiredArgsConstructor
public class BindingReleaseReadService {

    /** 固定专用排障权限，不扩大已有通知读权限。 */
    public static final String READ_PERMISSION = "asset:binding:read";
    /** 有界索引 SQL，必须通过独立 Spring 事务 Bean 调用。 */
    private final BindingReleaseReadMapper mapper;
    /** 服务端实时事实权限，无允许全部或故障放行实现。 */
    private final OutboxOperationsAuthorizer authority;

    /** 同时间 UUID 断点，不声称状态并发变化时跨页一致。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingReleasePage dead(long actor, BindingReleaseCursor cursor, int limit) {
        authorize(actor);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("分页大小必须为 1～50");
        if (cursor != null) {
            var time = cursor.createdAt();
            if (time == null || time.getNano() % 1000 != 0 || time.getYear() < 1000 || time.getYear() > 9999) {
                throw new IllegalArgumentException("游标时间最多微秒精度，年份必须为 1000～9999");
            }
            cursor = new BindingReleaseCursor(time, canonicalId(cursor.requestId()));
        }
        var rows = mapper.dead(cursor, limit + 1);
        var items = rows.stream().limit(limit).map(BindingReleaseReadService::view).toList();
        var next =
            rows.size() > limit
                ? new BindingReleaseCursor(items.getLast().createdAt(), items.getLast().requestId())
                : null;
        return new BindingReleasePage(items, next);
    }

    /** 可读取已离开 DEAD 的原请求，失败不是成功空态。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingReleaseView detail(long actor, String requestId) {
        authorize(actor);
        var row = mapper.detail(canonicalId(requestId));
        if (row == null) throw new OperationsNotFoundException("绑定释放任务不存在");
        return view(row);
    }

    /** 数量达到 1001 即为下界；数据库故障由外层转换为明确不可用。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingReleaseSnapshot snapshot(long actor) {
        authorize(actor);
        return mapper.snapshot();
    }

    private void authorize(long actor) {
        if (actor <= 0) throw new IllegalArgumentException("操作者标识无效");
        authority.requirePermission(actor, READ_PERMISSION);
    }

    private static BindingReleaseView view(BindingRelease row) {
        String failure = row.getLastFailure();
        // 即使旧数据异常也不将原始错误转发到客户端；固定类别以外均显示 unknown。
        if (failure != null && !Set.of("release-unconfirmed", "lease-exhausted").contains(failure)) failure = "unknown";
        return new BindingReleaseView(
            row.getRequestId(),
            row.getAssetId(),
            row.getPurpose(),
            row.getStatus(),
            row.getAttempts(),
            row.getReplayGeneration(),
            row.getGenerationAttempts(),
            failure,
            row.getCreatedAt(),
            row.getUpdatedAt(),
            row.getNextAttemptAt()
        );
    }

    private static String canonicalId(String value) {
        if (
            value == null ||
            !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        ) {
            throw new IllegalArgumentException("原绑定请求必须为标准 UUID");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
