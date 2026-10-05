package cn.kokonexus.chat.interfaces;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Knife4j 配置聚合已实现契约，所有入口仍经过网关会话验证。 */
@Hidden
@io.swagger.v3.oas.annotations.tags.Tag(name = "DocumentationController")
@RestController
public class DocumentationController {

    /** 公开 API 前缀，无主机或密钥。 */
    private final String base;

    public DocumentationController(@Value("${koko.public-api-base:/api}") String base) {
        this.base = base;
    }

    @GetMapping("/api/chat/docs/v3/api-docs/swagger-config")
    @io.swagger.v3.oas.annotations.Operation(summary = "configuration 业务接口")
    public Map<String, Object> configuration() {
        return Map.of(
            "urls",
            List.of(
                Map.of("name", "消息与群聊", "url", base + "/chat/docs/v3/api-docs"),
                Map.of("name", "账号与创作者", "url", base + "/docs/gateway/v3/api-docs"),
                Map.of("name", "社区与文章", "url", base + "/docs/community/v3/api-docs"),
                Map.of("name", "直播", "url", base + "/docs/live/v3/api-docs"),
                Map.of("name", "语音房", "url", base + "/docs/voice/v3/api-docs"),
                Map.of("name", "通知", "url", base + "/docs/notification/v3/api-docs"),
                Map.of("name", "媒体资产", "url", base + "/docs/asset/v3/api-docs")
            )
        );
    }
}
