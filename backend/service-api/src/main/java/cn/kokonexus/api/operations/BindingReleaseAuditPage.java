package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 每个原绑定最多十代审计；分页仍有硬上限。 */
public record BindingReleaseAuditPage(
    @Schema(description = "当前审计页") List<BindingReleaseAuditView> items,
    @Schema(description = "exclusive 下一页代次，无更多为空") Integer nextGeneration
) implements java.io.Serializable {
    public BindingReleaseAuditPage {
        items = List.copyOf(items);
    }
}
