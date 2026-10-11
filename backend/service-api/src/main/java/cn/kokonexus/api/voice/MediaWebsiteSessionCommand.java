package cn.kokonexus.api.voice;

import java.io.Serializable;

/** 内部网站注销登记；只能由可信网关在确认网站会话撤销后调用，不暴露为公共HTTP参数。 */
public record MediaWebsiteSessionCommand(
    /** 网关确认的网站用户正数long字符串。 */ String userId,
    /** 域隔离的高熵网站会话摘要，禁止输出日志。 */ String websiteScope
) implements Serializable {
    @Override
    public String toString() {
        return "MediaWebsiteSessionCommand[redacted]";
    }
}
