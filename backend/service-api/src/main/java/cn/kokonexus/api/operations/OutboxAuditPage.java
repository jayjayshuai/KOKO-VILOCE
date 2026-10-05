package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 审计按 accepted_generation 降序分页；单事件人工代次上限十，不扫全站审计。 */
public record OutboxAuditPage(
    @Schema(description = "当前事件有界审计列表，最多二十条") List<OutboxAuditView> items,
    @Schema(description = "下一页 exclusive 代次，无更多时为空") Long nextGeneration
) implements java.io.Serializable {
    public OutboxAuditPage {
        items = List.copyOf(items);
    }
}
