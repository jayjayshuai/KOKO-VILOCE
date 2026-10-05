package cn.kokonexus.outbox.persistence;

import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.outbox.binding.BindingRelease;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 固定表、无任意排序或动态 SQL；索引分页和每状态最多 1001 行采样。 */
public interface BindingReleaseReadMapper {
    /** 查询 limit+1 行以判断下一页，不使用无界 OFFSET。 */
    List<BindingRelease> dead(@Param("cursor") BindingReleaseCursor cursor, @Param("limit") int limit);
    /** 只读安全列，永不查询领取令牌和所有者。 */
    BindingRelease detail(@Param("requestId") String requestId);
    /** 同一 SQL 快照，时钟与数据都来自当前业务库。 */
    BindingReleaseSnapshot snapshot();
}
