package cn.kokonexus.community.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.outbox.binding.BindingAttemptReconciler;
import cn.kokonexus.outbox.binding.BindingReleaseMetrics;
import cn.kokonexus.outbox.binding.BindingReleaseOperationsFacade;
import cn.kokonexus.outbox.binding.BindingReleaseRelay;
import cn.kokonexus.outbox.binding.BindingReleaseWriter;
import cn.kokonexus.outbox.operations.OutboxDomainAuthorization;
import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import cn.kokonexus.outbox.persistence.BindingReleaseMapper;
import cn.kokonexus.outbox.persistence.BindingReleaseReadMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 真实 Spring Bean/属性条件，不 mock 配置类，不替代完整 Boot/Nacos 发布验收。 */
class BindingReleaseConfigurationTest {

    @Test
    void bothDomainConfigurationsDefaultClosedAndNeverScheduleOrSample() {
        for (Class<?> configuration : configurations()) {
            var mapper = mock(BindingReleaseReadMapper.class);
            var authority = mock(OutboxDomainAuthorization.class);
            runner(configuration, mapper, authority).run(context -> {
                assertThat(context)
                    .hasSingleBean(BindingReleaseWriter.class)
                    .doesNotHaveBean(BindingAttemptReconciler.class)
                    .doesNotHaveBean(BindingReleaseRelay.class)
                    .doesNotHaveBean(BindingReleaseMetrics.class);
                assertThatThrownBy(() ->
                    context.getBean(BindingReleaseOperationsFacade.class).snapshot("10")
                ).isInstanceOf(OperationsUnavailableException.class);
                verifyNoInteractions(mapper, authority);
            });
        }
    }

    @Test
    void oneOperationsSwitchDoesNotOpenDomainAndMetricsRemainIndependent() {
        for (Class<?> configuration : configurations()) {
            var mapper = mock(BindingReleaseReadMapper.class);
            var authority = mock(OutboxDomainAuthorization.class);
            runner(configuration, mapper, authority)
                .withPropertyValues("koko.operations.enabled=true", "koko.asset-binding.metrics-enabled=true")
                .run(context -> {
                    assertThat(context)
                        .hasSingleBean(BindingReleaseMetrics.class)
                        .doesNotHaveBean(BindingReleaseRelay.class);
                    assertThatThrownBy(() ->
                        context.getBean(BindingReleaseOperationsFacade.class).snapshot("10")
                    ).isInstanceOf(OperationsUnavailableException.class);
                    verifyNoInteractions(mapper, authority);
                });
        }
    }

    private static Class<?>[] configurations() {
        return new Class<?>[] { BindingReleaseConfiguration.class };
    }

    private static ApplicationContextRunner runner(
        Class<?> configuration,
        BindingReleaseReadMapper mapper,
        OutboxDomainAuthorization authority
    ) {
        return new ApplicationContextRunner()
            .withUserConfiguration(configuration)
            .withBean(BindingReleaseMapper.class, () -> mock(BindingReleaseMapper.class))
            .withBean(BindingAttemptMapper.class, () -> mock(BindingAttemptMapper.class))
            .withBean(BindingReleaseReadMapper.class, () -> mapper)
            .withBean(OutboxDomainAuthorization.class, () -> authority)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }
}
