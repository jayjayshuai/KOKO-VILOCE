package cn.kokonexus.gateway.configuration;

import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 网关的账号/创作者接口也纳入登录后的文档。 */
@Configuration
public class ApiDocumentationConfiguration {

    @Bean
    OpenApiCustomizer externalApiPaths(@Value("${koko.public-api-base:/api}") String base) {
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
