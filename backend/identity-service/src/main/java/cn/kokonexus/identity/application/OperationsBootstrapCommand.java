package cn.kokonexus.identity.application;

import java.time.LocalDateTime;
import java.util.Locale;

/** 只供负责人离线工具使用，不注册 HTTP/RPC，不含密码或默认账号。 */
public record OperationsBootstrapCommand(
    /** 首次审批 UUID；重试必须保留。 */
    String requestId,
    /** 负责人明确指定的现有账号 ID。 */
    long userId,
    /** 显式核对的账号标识，防止只复制了错误 ID。 */
    String expectedHandle,
    /** 必填上海时间，数据库检查未来且不超过一天。 */
    LocalDateTime expiresAt,
    /** 审批原因/工单，不能填写密钥。 */
    String reason
) {
    public OperationsBootstrapCommand normalized() {
        if (
            requestId == null ||
            !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ||
            userId <= 0 ||
            expectedHandle == null ||
            expectedHandle.isBlank() ||
            expectedHandle.length() > 80 ||
            !expectedHandle.equals(expectedHandle.strip()) ||
            expiresAt == null ||
            expiresAt.getNano() % 1000 != 0 ||
            expiresAt.getYear() < 1000 ||
            expiresAt.getYear() > 9999
        ) {
            throw new IllegalArgumentException("初始化账号、标识、受理 UUID 或微秒到期时间无效");
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.length() < 10 || normalizedReason.length() > 500) {
            throw new IllegalArgumentException("初始化原因须为 10～500 字符");
        }
        return new OperationsBootstrapCommand(
            requestId.toLowerCase(Locale.ROOT),
            userId,
            expectedHandle,
            expiresAt,
            normalizedReason
        );
    }

    @Override
    public String toString() {
        return "OperationsBootstrapCommand[requestId=" + requestId + ", reason=<redacted>]";
    }
}
