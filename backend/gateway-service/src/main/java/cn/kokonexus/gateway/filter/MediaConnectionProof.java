package cn.kokonexus.gateway.filter;

import cn.kokonexus.api.voice.MediaAdmissionCommand;

/** 单次握手已核验的秘密，仅内存保持，不序列化/记录或跨连接复用。 */
public record MediaConnectionProof(
    /** 网关身份与媒体JWT，不接收客户端自行声明身份。 */ MediaAdmissionCommand admission,
    /** 原网站会话，用于注销/冻结后关闭该连接。 */ String websiteToken
) {
    /** 请求属性键，无凭据内容。 */ public static final String ATTRIBUTE = "koko.media.connection-proof";

    @Override
    public String toString() {
        return "MediaConnectionProof[redacted]";
    }
}
