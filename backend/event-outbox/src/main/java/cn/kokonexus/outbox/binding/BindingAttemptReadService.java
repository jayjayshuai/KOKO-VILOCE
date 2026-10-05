package cn.kokonexus.outbox.binding;

import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.api.operations.BindingAttemptPage;
import cn.kokonexus.api.operations.BindingAttemptView;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.outbox.operations.OutboxOperationsAuthorizer;
import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 当前专用读权限与新协议事实查询，不在读取过程中封存/触发RPC。 */
@RequiredArgsConstructor
public class BindingAttemptReadService {

    /** 当前域MP事实映射，查询时不持写锁。 */
    private final BindingAttemptMapper mapper;
    /** 实时授权，通知权限与管理员不隐含本权限。 */
    private final OutboxOperationsAuthorizer authority;

    /** 有界OPEN索引页；跨页并发变化需刷新，不对不存在的旧协议意图推断结束。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingAttemptPage open(long actor, BindingReleaseCursor cursor, int limit) {
        authorize(actor);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("分页大小必须为1～50");
        if (cursor != null) {
            var time = cursor.createdAt();
            if (time == null || time.getNano() % 1000 != 0 || time.getYear() < 1000 || time.getYear() > 9999) {
                throw new IllegalArgumentException("游标时点须保留数据库微秒精度");
            }
            cursor = new BindingReleaseCursor(time, canonical(cursor.requestId()));
        }
        var rows = mapper.openPage(cursor, limit + 1);
        var items = rows.stream().limit(limit).map(BindingAttemptReadService::view).toList();
        var next =
            rows.size() > limit
                ? new BindingReleaseCursor(items.getLast().createdAt(), items.getLast().requestId())
                : null;
        return new BindingAttemptPage(items, next);
    }

    /** COMMITTED/ABORTED亦可读取；404只表示没有新协议行。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public BindingAttemptView detail(long actor, String requestId) {
        authorize(actor);
        var row = mapper.selectById(canonical(requestId));
        if (row == null) throw new OperationsNotFoundException("新协议绑定凭据不存在；不能据此推断旧意图已结束");
        return view(row);
    }

    private void authorize(long actor) {
        if (actor <= 0) throw new IllegalArgumentException("操作者标识无效");
        authority.requirePermission(actor, BindingReleaseReadService.READ_PERMISSION);
    }

    private static String canonical(String id) {
        String normalized = AssetUrl.canonicalId(id == null ? null : id.toLowerCase(java.util.Locale.ROOT));
        if (normalized == null) throw new IllegalArgumentException("原绑定请求须为标准UUID");
        return normalized;
    }

    private static BindingAttemptView view(BindingAttempt row) {
        return new BindingAttemptView(
            row.getRequestId(),
            row.getAssetId(),
            row.getPurpose(),
            row.getStatus(),
            row.getCreatedAt(),
            row.getUpdatedAt()
        );
    }
}
