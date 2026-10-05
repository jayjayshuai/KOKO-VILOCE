package cn.kokonexus.voice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** 真实应用YAML与当前Boot绑定；不是完整Boot/Nacos/Redis/媒体启动验收。 */
class VoiceStartupConfigurationTest {

    @Test
    void realYamlKeepsCoreDisabledAndBindsBoundedServletResources() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals(
            "${VOICE_INTERACTION_CORE_ENABLED:false}",
            properties.getProperty("koko.voice.interaction-core-enabled")
        );
        var values = new HashMap<String, Object>();
        properties.forEach((key, value) -> values.put(key.toString(), value));
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("actual-voice-yaml", values));
        var server = Binder.get(environment).bind("server", ServerProperties.class).get();
        assertEquals(16, server.getTomcat().getThreads().getMax());
        assertEquals(2, server.getTomcat().getThreads().getMinSpare());
        assertEquals(32, server.getTomcat().getAcceptCount());
        assertEquals(512, server.getTomcat().getMaxConnections());
        assertEquals("4", properties.getProperty("spring.datasource.hikari.maximum-pool-size"));
    }
}
