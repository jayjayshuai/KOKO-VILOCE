package cn.kokonexus.gateway.infrastructure;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/** 使用当前共享网站会话读取，含冻结/超时；不因媒体轮询续活网站会话。 */
@Component
public class MediaWebsiteSessionCheck {

    public boolean active(String token, String user) {
        if (token == null || token.isBlank() || token.length() > 8192) return false;
        Object current = StpUtil.getLoginIdByToken(token);
        return current != null && user.equals(current.toString());
    }
}
