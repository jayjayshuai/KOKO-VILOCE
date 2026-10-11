package cn.kokonexus.api.voice;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;

/** 内部媒体准入请求；身份取自网关登录会话，不把JWT或请求字段用于日志。 */
public record MediaAdmissionCommand(
    @Schema(description = "网关确认的当前用户正数long字符串") String userId,
    @Schema(description = "LiveKit签名入会JWT，只用于核验，不打印或持久化") String token,
    @Schema(description = "可信网关生成的网站会话摘要，不接受客户端填写") String websiteScope
) implements Serializable {
    /** 旧RPC消费者保留反序列化兼容；缺摘要不能获得受控媒体权限。 */
    public MediaAdmissionCommand(String userId, String token) {
        this(userId, token, null);
    }

    @Override
    public String toString() {
        return "MediaAdmissionCommand[redacted]";
    }
}
