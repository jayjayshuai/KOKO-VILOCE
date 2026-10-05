package cn.kokonexus.api.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;

/** 聊天身份投影：不包含邮箱、密码或其他账号私密信息。 */
public record ChatIdentity(
    @Schema(description = "用户雪花 ID，字符串避免 JavaScript 精度损失") String id,
    @Schema(description = "用于精确查找的公开用户名") String handle,
    @Schema(description = "公开显示名称") String displayName
) implements Serializable {}
