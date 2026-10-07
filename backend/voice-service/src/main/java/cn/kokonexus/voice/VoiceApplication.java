package cn.kokonexus.voice;

import cn.kokonexus.common.api.ApiDocumentationConfiguration;
import cn.kokonexus.common.api.GlobalExceptionHandler;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** 语音服务入口；显式导入跨包异常处理，保持权限、状态与依赖失败的HTTP语义。 */
@SpringBootApplication
@EnableDubbo
@Import({ ApiDocumentationConfiguration.class, GlobalExceptionHandler.class })
public class VoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceApplication.class, args);
    }
}
