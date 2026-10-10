package cn.kokonexus.api.identity;

import java.io.Serializable;

/** 平台公共契约：请求契约；字段校验以公开接口约束为准。 */
public record RegisterIdentityCommand(
    @io.swagger.v3.oas.annotations.media.Schema(description = "登录邮箱，个人敏感信息") String email,
    @io.swagger.v3.oas.annotations.media.Schema(description = "登录密码，仅输入使用，禁止日志输出") String password,
    @io.swagger.v3.oas.annotations.media.Schema(description = "公开用户名，用于精确查询") String handle,
    @io.swagger.v3.oas.annotations.media.Schema(description = "用户公开显示名称") String displayName
) implements Serializable {
    /** 保持RPC字段与序列化兼容，只屏蔽诊断文本中的密码/邮箱。 */
    @Override
    public String toString() {
        return "RegisterIdentityCommand[redacted]";
    }
}
