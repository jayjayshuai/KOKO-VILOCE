package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 非跨页快照的有界 DEAD 页，不把数据库故障伪装成成功空队列。 */
public record BindingReleasePage(
    @Schema(description = "按创建时间、请求 UUID 降序，最多五十条") List<BindingReleaseView> items,
    @Schema(description = "有更多时返回 exclusive 断点，否则为空") BindingReleaseCursor nextCursor
) implements java.io.Serializable {
    public BindingReleasePage {
        items = List.copyOf(items);
    }
}
