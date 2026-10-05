package cn.kokonexus.outbox.operations;

import cn.kokonexus.outbox.persistence.OutboxOperationsMapper;
import cn.kokonexus.outbox.persistence.OutboxReplayMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅由三个发布域显式导入；用独立 Bean 保证调用真实 Spring 事务代理。 */
@Configuration
public class OutboxOperationsConfiguration {

    @Bean
    public OutboxReadService outboxReadService(OutboxOperationsMapper mapper, OutboxDomainAuthorization authority) {
        return new OutboxReadService(mapper, authority);
    }

    @Bean
    public OutboxReplayService outboxReplayService(OutboxReplayMapper mapper, OutboxDomainAuthorization authority) {
        return new OutboxReplayService(mapper, authority);
    }
}
