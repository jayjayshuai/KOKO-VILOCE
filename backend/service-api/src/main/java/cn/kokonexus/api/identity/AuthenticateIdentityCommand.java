package cn.kokonexus.api.identity;

import java.io.Serializable;

/** 平台公共契约：请求契约；字段校验以公开接口约束为准。 */
public record AuthenticateIdentityCommand(
    @io.swagger.v3.oas.annotations.media.Schema(description = "登录邮箱，个人敏感信息") String email,
    @io.swagger.v3.oas.annotations.media.Schema(description = "登录密码，仅输入使用，禁止日志输出") String password
) implements Serializable {
    /** RPC超时/诊断不应通过record默认文本暴露凭据。 */
    @Override
    public String toString() {
        return "AuthenticateIdentityCommand[redacted]";
    }
}
