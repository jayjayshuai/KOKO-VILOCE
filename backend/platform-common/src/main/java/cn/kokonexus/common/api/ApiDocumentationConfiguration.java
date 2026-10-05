package cn.kokonexus.common.api;

import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 反向代理把 /koko-api 改写为 /api；文档调试使用外部路径。 */
@Configuration
public class ApiDocumentationConfiguration {

    @Bean
    OpenApiCustomizer externalApiPaths(@Value("${KOKO_PUBLIC_API_BASE:${koko.public-api-base:/api}}") String base) {
        return api -> {
            Paths paths = new Paths();
            api.getPaths().forEach((path, operation) ->
                paths.addPathItem(path.startsWith("/api/") ? path.substring(4) : path, operation)
            );
            api.setPaths(paths);
            api.setServers(java.util.List.of(new Server().url(base)));
        };
    }
}
