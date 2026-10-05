package cn.kokonexus.api.operations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 长度前缀编码，并以独立动作标识与通知命令摘要隔离。 */
public final class BindingReleaseCommandBinding {

    private BindingReleaseCommandBinding() {}

    /** 大小写 UUID 规范化；不接受直播域/任意资源标识或无界原因。 */
    public static BindingReleaseReplayCommand normalize(String domain, BindingReleaseReplayCommand command) {
        if (
            !Set.of("identity", "community").contains(domain == null ? "" : domain) ||
            command == null ||
            command.expectedGeneration() < 0 ||
            command.expectedGeneration() > 10
        ) throw new IllegalArgumentException("绑定恢复域或代次无效");
        String reason = command.reason() == null ? "" : command.reason().strip();
        if (reason.codePointCount(0, reason.length()) < 10 || reason.length() > 500) throw new IllegalArgumentException(
            "恢复原因须为10～500字符"
        );
        return new BindingReleaseReplayCommand(
            uuid(command.commandId()),
            uuid(command.requestId()),
            command.expectedGeneration(),
            reason
        );
    }

    /** 原绑定和人工幂等键严格 UUID，不能用缩写或路径替代。 */
    public static String uuid(String value) {
        if (
            value == null ||
            !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        ) throw new IllegalArgumentException("标识必须为标准UUID");
        return value.toLowerCase(Locale.ROOT);
    }

    /** 绑定整个动作、账号、域、目标、人工命令、代次和原因；不复用通知编码协议。 */
    public static String fingerprint(long actor, String domain, BindingReleaseReplayCommand command) {
        var normalized = normalize(domain, command);
        if (actor <= 0) throw new IllegalArgumentException("操作者无效");
        try {
            var bytes = new ByteArrayOutputStream();
            try (var fields = new DataOutputStream(bytes)) {
                fields.writeInt(1);
                write(fields, "asset-binding-release-recovery");
                fields.writeLong(actor);
                write(fields, domain);
                write(fields, normalized.commandId());
                write(fields, normalized.requestId());
                fields.writeInt(normalized.expectedGeneration());
                write(fields, normalized.reason());
            }
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            );
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("恢复摘要不可用", unavailable);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("恢复命令编码失败", impossible);
        }
    }

    private static void write(DataOutputStream stream, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        stream.writeInt(bytes.length);
        stream.write(bytes);
    }
}
