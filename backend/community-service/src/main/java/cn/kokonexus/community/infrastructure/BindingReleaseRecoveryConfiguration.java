package cn.kokonexus.community.infrastructure;

import cn.kokonexus.outbox.binding.BindingReleaseRecoveryFacade;
import cn.kokonexus.outbox.binding.BindingReleaseRecoveryService;
import cn.kokonexus.outbox.binding.BindingReleaseReplayAuthorization;
import cn.kokonexus.outbox.persistence.BindingReleaseRecoveryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** community域独立恢复配置，默认关闭但保持事务代理；不注入直播域。 */
@Configuration
public class BindingReleaseRecoveryConfiguration {

    /** 核心与审计必须经过独立 Spring 事务 Bean。 */
    @Bean
    public BindingReleaseRecoveryService bindingReleaseRecoveryService(
        BindingReleaseRecoveryMapper mapper,
        BindingReleaseReplayAuthorization authority
    ) {
        return new BindingReleaseRecoveryService("community", mapper, authority);
    }

    /** 读审计与写受理各自受服务器开关约束。 */
    @Bean
    public BindingReleaseRecoveryFacade bindingReleaseRecoveryFacade(
        BindingReleaseRecoveryService service,
        BindingReleaseReplayAuthorization authority,
        @Value("${koko.operations.enabled:false}") boolean operationsEnabled,
        @Value("${koko.asset-binding.operations-enabled:false}") boolean readEnabled,
        @Value("${koko.asset-binding.replay-enabled:false}") boolean replayEnabled
    ) {
        return new BindingReleaseRecoveryFacade(
            "community",
            operationsEnabled && readEnabled,
            replayEnabled,
            service,
            authority
        );
    }
}
