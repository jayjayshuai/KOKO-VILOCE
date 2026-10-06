package cn.kokonexus.voice;

import cn.kokonexus.common.api.ApiDocumentationConfiguration;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** voice-service：VoiceApplication 领域类型；字段单位、状态及可空性见各属性说明。 */
@SpringBootApplication
@EnableDubbo
@Import(ApiDocumentationConfiguration.class)
public class VoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceApplication.class, args);
    }
}
