package cn.kokonexus.voice;

import static org.junit.jupiter.api.Assertions.*;

import cn.kokonexus.common.api.ApiDocumentationConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

/** 防止语音服务漏装公共文档配置，导致 Knife4j 调试错误地访问内部地址。 */
class VoiceDocumentationTest {

    @Test
    void voiceApplicationImportsPublicDocumentation() {
        assertTrue(
            java.util.List.of(VoiceApplication.class.getAnnotation(Import.class).value()).contains(
                ApiDocumentationConfiguration.class
            )
        );
    }

    @Test
    void externalPathsAndServerMatchReverseProxy() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", java.util.Map.of("KOKO_PUBLIC_API_BASE", "/koko-api")));
            context.register(ApiDocumentationConfiguration.class);
            context.refresh();
            var api = new OpenAPI().paths(new Paths().addPathItem("/api/voice/rooms", new PathItem()));
            context.getBean(OpenApiCustomizer.class).customise(api);
            assertEquals("/koko-api", api.getServers().getFirst().getUrl());
            assertTrue(api.getPaths().containsKey("/voice/rooms"));
            assertFalse(api.getPaths().containsKey("/api/voice/rooms"));
        }
    }
}
