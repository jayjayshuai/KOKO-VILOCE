package cn.kokonexus.live.configuration;

import cn.kokonexus.outbox.operations.OutboxDomainAuthorization;
import cn.kokonexus.outbox.operations.OutboxOperationsConfiguration;
import cn.kokonexus.outbox.operations.OutboxOperationsFacade;
import cn.kokonexus.outbox.operations.OutboxReadService;
import cn.kokonexus.outbox.operations.OutboxReplayService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 固定 live 域与显式开关，客户端不能选择表名或重放其他库的事件。 */
@Configuration
@Import(OutboxOperationsConfiguration.class)
public class OutboxDomainConfiguration {

    @Bean
    public OutboxOperationsFacade outboxOperationsFacade(
        @Value("${koko.operations.enabled:false}") boolean enabled,
        OutboxReadService reads,
        OutboxReplayService replays,
        OutboxDomainAuthorization authority
    ) {
        return new OutboxOperationsFacade("live", enabled, reads, replays, authority);
    }
}
