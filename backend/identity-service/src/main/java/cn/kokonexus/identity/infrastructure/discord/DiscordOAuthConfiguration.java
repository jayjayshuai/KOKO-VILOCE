package cn.kokonexus.identity.infrastructure.discord;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认不创建外部客户端；显式开启必须同时通过应用/回调/加密配置校验。 */
@Configuration
@EnableConfigurationProperties(DiscordOAuthSettings.class)
public class DiscordOAuthConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "koko.discord", name = "enabled", havingValue = "true")
    public DiscordOAuthClient discordOAuthClient(DiscordOAuthSettings settings) {
        return new DiscordOAuthClient(settings);
    }

    @Bean
    @ConditionalOnProperty(prefix = "koko.discord", name = "enabled", havingValue = "true")
    public DiscordTokenCipher discordTokenCipher(DiscordOAuthSettings settings) {
        settings.validate();
        return new DiscordTokenCipher(settings.getEncryptionKeyId(), settings.keyRing());
    }
}
