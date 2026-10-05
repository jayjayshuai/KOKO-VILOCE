package cn.kokonexus.community.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.outbox.binding.BindingReleaseRecoveryFacade;
import cn.kokonexus.outbox.binding.BindingReleaseReplayAuthorization;
import cn.kokonexus.outbox.persistence.BindingReleaseRecoveryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 实际配置条件，默认或缺一个开关均不调用恢复事实表/密码确认。 */
class BindingReleaseRecoveryConfigurationTest {

    @Test
    void defaultAndAnyMissingFeatureSwitchCannotReplay() {
        for (int missing = 0; missing < 3; missing++) {
            var mapper = mock(BindingReleaseRecoveryMapper.class);
            var authority = mock(BindingReleaseReplayAuthorization.class);
            new ApplicationContextRunner()
                .withUserConfiguration(BindingReleaseRecoveryConfiguration.class)
                .withBean(BindingReleaseRecoveryMapper.class, () -> mapper)
                .withBean(BindingReleaseReplayAuthorization.class, () -> authority)
                .withPropertyValues(
                    "koko.operations.enabled=" + (missing != 0),
                    "koko.asset-binding.operations-enabled=" + (missing != 1),
                    "koko.asset-binding.replay-enabled=" + (missing != 2)
                )
                .run(context -> {
                    var facade = context.getBean(BindingReleaseRecoveryFacade.class);
                    assertThatThrownBy(() ->
                        facade.replay(
                            "10",
                            "session",
                            new BindingReleaseReplayCommand(
                                "81000000-0000-4000-8000-000000000001",
                                "81000000-0000-4000-8000-000000000002",
                                0,
                                "隔离工单恢复原因不少于十字"
                            ),
                            "secret"
                        )
                    ).isInstanceOf(OperationsUnavailableException.class);
                    verifyNoInteractions(mapper, authority);
                });
        }
    }
}
