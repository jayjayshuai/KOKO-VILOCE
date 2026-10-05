package cn.kokonexus.chat.interfaces;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 使用 Knife4j 纯 UI，避免将不兼容的旧 Springdoc starter 引入 Boot 3.5。 */
@Configuration
public class ChatDocumentation implements WebMvcConfigurer {

    @Bean
    OpenAPI chatOpenApi() {
        return new OpenAPI().info(
            new Info()
                .title("KOKO Nexus Messaging API")
                .version("1.0")
                .description("登录后使用；消息序号和成员权限由服务端验证。")
        );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/chat/docs/**").addResourceLocations("classpath:/META-INF/resources/");
    }
}
