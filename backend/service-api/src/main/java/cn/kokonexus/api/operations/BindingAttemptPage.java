package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 新协议OPEN有界游标页，并发终态变化不提供跨页快照保证。 */
public record BindingAttemptPage(
    @Schema(description = "最多50条新协议OPEN凭据") List<BindingAttemptView> items,
    @Schema(description = "exclusive升序断点，可空；不舍弃微秒") BindingReleaseCursor nextCursor
) implements java.io.Serializable {}
