package cn.kokonexus.asset.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 两个业务域的只读引用快照，不是分布式原子快照或资产删除许可。 */
@Schema(description = "本人图片的全状态引用；不改变公开读取授权，不代表可安全删除")
public record AssetReferenceSnapshot(
    @Schema(description = "任意状态主页的头像或封面是否引用此图片") boolean profileReferenced,
    @Schema(description = "任意状态文章是否引用此图片，含草稿和归档") boolean postReferenced,
    @Schema(description = "两域查询完成的 UTC 时间，含偏移；不保证同一事务时间点") OffsetDateTime checkedAt
) {}
