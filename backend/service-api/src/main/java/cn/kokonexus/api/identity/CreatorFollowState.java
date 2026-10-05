package cn.kokonexus.api.identity;

import java.io.Serializable;

/** 平台公共契约：CreatorFollowState 领域类型；字段单位、状态及可空性见各属性说明。 */
public record CreatorFollowState(
    @io.swagger.v3.oas.annotations.media.Schema(description = "创作者用户 ID") String creatorId,
    @io.swagger.v3.oas.annotations.media.Schema(description = "关注人数") long followerCount,
    @io.swagger.v3.oas.annotations.media.Schema(description = "当前用户是否已关注") boolean followedByMe
) implements Serializable {}
