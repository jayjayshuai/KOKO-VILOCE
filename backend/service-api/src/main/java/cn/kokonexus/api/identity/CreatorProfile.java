package cn.kokonexus.api.identity;

import java.io.Serializable;

/** 平台公共契约：CreatorProfile 领域类型；字段单位、状态及可空性见各属性说明。 */
public record CreatorProfile(
    @io.swagger.v3.oas.annotations.media.Schema(description = "操作用户 ID") String userId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "公开访问路径标识") String slug,
    @io.swagger.v3.oas.annotations.media.Schema(description = "用户公开显示名称") String displayName,
    @io.swagger.v3.oas.annotations.media.Schema(description = "创作者主页短介绍") String headline,
    @io.swagger.v3.oas.annotations.media.Schema(description = "创作者个人简介") String bio,
    @io.swagger.v3.oas.annotations.media.Schema(description = "公开头像地址") String avatarUrl,
    @io.swagger.v3.oas.annotations.media.Schema(description = "公开横幅地址") String bannerUrl,
    @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的头像资产 UUID") String avatarAssetId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "经归属验证的横幅资产 UUID") String bannerAssetId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "业务状态，允许值以所属领域状态机为准") String status,
    @io.swagger.v3.oas.annotations.media.Schema(description = "乐观锁版本，修改必须携带当前值") long version,
    @io.swagger.v3.oas.annotations.media.Schema(description = "关注人数") long followerCount
) implements Serializable {}
