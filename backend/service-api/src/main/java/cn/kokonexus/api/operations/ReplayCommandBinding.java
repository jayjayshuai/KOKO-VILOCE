package cn.kokonexus.api.operations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 命令绑定采用定长计数/长度前缀字段，不依赖 JSON 顺序，不通过拼接分隔符制造碰撞。 */
public final class ReplayCommandBinding {

    /** 固定业务域白名单，不允许客户端传表名或 Dubbo group。 */
    private static final Set<String> DOMAINS = Set.of("identity", "community", "live");
    /** 严格原始事件/请求 UUID。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private ReplayCommandBinding() {}

    /** 严格规范化与核心相同的命令，错误输入在密码验证前拒绝。 */
    public static OutboxReplayCommand normalize(String domain, OutboxReplayCommand command) {
        if (domain == null || !DOMAINS.contains(domain) || command == null || command.expectedGeneration() < 0) {
            throw new IllegalArgumentException("业务域或重放命令无效");
        }
        String reason = command.reason() == null ? "" : command.reason().strip();
        if (reason.length() < 10 || reason.length() > 500) throw new IllegalArgumentException("重放原因长度无效");
        return new OutboxReplayCommand(
            uuid(command.requestId()),
            uuid(command.eventId()),
            command.expectedGeneration(),
            reason
        );
    }

    /** 绑定操作者和业务域，禁止同凭据改目标、代次、原因或受理 ID。 */
    public static String fingerprint(long operatorId, String domain, OutboxReplayCommand command) {
        var normalized = normalize(domain, command);
        if (operatorId <= 0) throw new IllegalArgumentException("操作者标识无效");
        try {
            var bytes = new ByteArrayOutputStream();
            try (var fields = new DataOutputStream(bytes)) {
                fields.writeInt(1); // 编码协议版本，未来扩展必须区分旧确认凭据。
                fields.writeLong(operatorId);
                write(fields, domain);
                write(fields, normalized.requestId());
                write(fields, normalized.eventId());
                fields.writeLong(normalized.expectedGeneration());
                write(fields, normalized.reason());
            }
            return sha256(bytes.toByteArray());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("命令绑定编码失败", failure);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void write(DataOutputStream fields, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        fields.writeInt(bytes.length);
        fields.write(bytes);
    }

    private static String uuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new IllegalArgumentException(
            "UUID 格式无效"
        );
        return value.toLowerCase(Locale.ROOT);
    }
}
