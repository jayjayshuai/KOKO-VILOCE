package cn.kokonexus.api.identity;

import java.io.Serializable;
import java.util.List;

/** 平台公共契约：CreatorPage 领域类型；字段单位、状态及可空性见各属性说明。 */
public record CreatorPage(
    @io.swagger.v3.oas.annotations.media.Schema(description = "当前页的业务投影列表") List<CreatorProfile> items,
    @io.swagger.v3.oas.annotations.media.Schema(description = "页码，从 1 开始") int page,
    @io.swagger.v3.oas.annotations.media.Schema(description = "单页条数，受接口最大值限制") int size,
    @io.swagger.v3.oas.annotations.media.Schema(description = "匹配条件的总条数") long total
) implements Serializable {}
