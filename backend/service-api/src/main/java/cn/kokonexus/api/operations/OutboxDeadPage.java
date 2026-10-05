package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 有界游标分页，无无限 COUNT/offset；读取期间新产生的死信需刷新首页才能见到。 */
public record OutboxDeadPage(
    @Schema(description = "created_at/id 降序的一页 DEAD，最多五十条") List<OutboxEventView> items,
    @Schema(description = "下一页复合游标，无更多记录时为空") OutboxEventCursor nextCursor
) implements java.io.Serializable {
    public OutboxDeadPage {
        items = List.copyOf(items);
    }
}
