package cn.kokonexus.api.identity;

import java.io.Serializable;

/** 平台公共契约：UserIdentity 领域类型；字段单位、状态及可空性见各属性说明。 */
public record UserIdentity(
    @io.swagger.v3.oas.annotations.media.Schema(description = "唯一业务标识；HTTP 用户/雪花 ID 使用字符串表示")
    String id,
    @io.swagger.v3.oas.annotations.media.Schema(description = "登录邮箱，个人敏感信息") String email,
    @io.swagger.v3.oas.annotations.media.Schema(description = "公开用户名，用于精确查询") String handle,
    @io.swagger.v3.oas.annotations.media.Schema(description = "用户公开显示名称") String displayName,
    @io.swagger.v3.oas.annotations.media.Schema(description = "公开头像地址") String avatarUrl
) implements Serializable {}
