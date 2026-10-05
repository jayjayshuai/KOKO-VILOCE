package cn.kokonexus.identity.infrastructure;

import cn.kokonexus.outbox.binding.BindingAttemptCoordinator;
import cn.kokonexus.outbox.binding.BindingAttemptOperationsFacade;
import cn.kokonexus.outbox.binding.BindingAttemptReadService;
import cn.kokonexus.outbox.binding.BindingAttemptReconciler;
import cn.kokonexus.outbox.binding.BindingAttemptReservation;
import cn.kokonexus.outbox.binding.BindingAttemptResolution;
import cn.kokonexus.outbox.binding.BindingReleaseMetrics;
import cn.kokonexus.outbox.binding.BindingReleaseOperationsFacade;
import cn.kokonexus.outbox.binding.BindingReleaseReadService;
import cn.kokonexus.outbox.binding.BindingReleaseRelay;
import cn.kokonexus.outbox.binding.BindingReleaseWriter;
import cn.kokonexus.outbox.operations.OutboxDomainAuthorization;
import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import cn.kokonexus.outbox.persistence.BindingReleaseMapper;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅身份写域启用绑定释放，不将额外表或任务引入直播域。 */
@Configuration
public class BindingReleaseConfiguration {

    /** 独立只读事务代理，每次读取前重新校验专用权限。 */
    @Bean
    public BindingReleaseReadService bindingReleaseReadService(
        BindingReleaseReadMapper mapper,
        OutboxDomainAuthorization authority
    ) {
        return new BindingReleaseReadService(mapper, authority);
    }

    /** 双开关默认关闭，内部 RPC 也不能绕过未批准开放的排障入口。 */
    @Bean
    public BindingReleaseOperationsFacade bindingReleaseOperationsFacade(
        BindingReleaseReadService reads,
        @Value("${koko.operations.enabled:false}") boolean operationsEnabled,
        @Value("${koko.asset-binding.operations-enabled:false}") boolean bindingEnabled
    ) {
        return new BindingReleaseOperationsFacade(operationsEnabled && bindingEnabled, reads);
    }

    /** 新索引迁移后按发布计划显式启用，不扩大发布域以外的 SQL 查询。 */
    @Bean
    @ConditionalOnProperty(prefix = "koko.asset-binding", name = "metrics-enabled", havingValue = "true")
    public BindingReleaseMetrics bindingReleaseMetrics(BindingReleaseReadMapper mapper, MeterRegistry registry) {
        return new BindingReleaseMetrics(mapper, registry);
    }

    /** 新协议只读与当前专用权限，经独立事务代理访问。 */
    @Bean
    public BindingAttemptReadService bindingAttemptReadService(
        BindingAttemptMapper mapper,
        OutboxDomainAuthorization authority
    ) {
        return new BindingAttemptReadService(mapper, authority);
    }

    /** 与释放排障相同双开关，但没有人工封存写接口。 */
    @Bean
    public BindingAttemptOperationsFacade bindingAttemptOperationsFacade(
        BindingAttemptReadService reads,
        @Value("${koko.operations.enabled:false}") boolean operationsEnabled,
        @Value("${koko.asset-binding.operations-enabled:false}") boolean bindingEnabled
    ) {
        return new BindingAttemptOperationsFacade(operationsEnabled && bindingEnabled, reads);
    }

    /** 新协议凭据预登记的独立事务代理，不由补偿开关控制。 */
    @Bean
    public BindingAttemptReservation bindingAttemptReservation(BindingAttemptMapper mapper) {
        return new BindingAttemptReservation(mapper);
    }

    /** 已知回滚/安全核对独立封存，任务登记失败全事务回滚。 */
    @Bean
    public BindingAttemptResolution bindingAttemptResolution(BindingAttemptMapper mapper, BindingReleaseWriter writer) {
        return new BindingAttemptResolution(mapper, writer);
    }

    /** 必须与业务事务同一数据库/事务管理器，不在RPC线程直接持久化。 */
    @Bean
    public BindingAttemptCoordinator bindingAttemptCoordinator(
        BindingAttemptReservation reservation,
        BindingAttemptMapper mapper,
        BindingReleaseWriter writer,
        BindingAttemptResolution resolution
    ) {
        return new BindingAttemptCoordinator(reservation, mapper, writer, resolution);
    }

    /** 只处理新协议OPEN，默认关闭，须先停所有旧写入者再批准启用。 */
    @Bean
    @ConditionalOnProperty(prefix = "koko.asset-binding", name = "attempt-reconcile-enabled", havingValue = "true")
    public BindingAttemptReconciler bindingAttemptReconciler(BindingAttemptResolution resolution) {
        return new BindingAttemptReconciler(resolution);
    }

    /** 始终登记终态后释放任务，开关仅控制异步调度，不关闭事务一致性。 */
    @Bean
    public BindingReleaseWriter bindingReleaseWriter(BindingReleaseMapper mapper) {
        return new BindingReleaseWriter(mapper);
    }

    /** 发布前默认不启用；先升级资产 V5/seal Provider，再批准启用补偿。 */
    @Bean
    @ConditionalOnProperty(prefix = "koko.asset-binding", name = "release-enabled", havingValue = "true")
    public BindingReleaseRelay bindingReleaseRelay(BindingReleaseMapper mapper, AssetBindingValidator validator) {
        return new BindingReleaseRelay(mapper, validator::releaseCommitted);
    }
}
