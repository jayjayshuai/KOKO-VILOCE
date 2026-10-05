package cn.kokonexus.api.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 短期且绑定单个命令的秘密凭据，仅响应时发送，禁止日志/持久浏览器存储。 */
public record ReplayConfirmation(
    @Schema(description = "随机二次确认凭据，禁止输出日志或写入 localStorage") String confirmationToken,
    @Schema(description = "数据库到期时间，Asia/Shanghai，固定五分钟") LocalDateTime expiresAt
) implements java.io.Serializable {
    @Override
    public String toString() {
        return "ReplayConfirmation[confirmationToken=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
