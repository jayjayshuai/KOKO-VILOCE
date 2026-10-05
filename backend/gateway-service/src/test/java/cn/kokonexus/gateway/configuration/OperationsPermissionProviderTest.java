package cn.kokonexus.gateway.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.gateway.infrastructure.OperationsAuthorizationClient;
import cn.kokonexus.gateway.interfaces.OperationsAccessController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OperationsPermissionProviderTest {

    /** 当前 RPC 事实边界。 */
    private final OperationsAuthorizationClient client = mock(OperationsAuthorizationClient.class);
    /** Sa-Token Provider 不持有用户授权缓存。 */
    private final OperationsPermissionProvider provider = new OperationsPermissionProvider(client);

    @Test
    void unrelatedLoginTypeAndMissingIdentityHaveNoPermissions() {
        assertThat(provider.getPermissionList("10", "admin")).isEmpty();
        assertThat(provider.getRoleList(null, "login")).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void readsFreshPermissionsAndNeverAllowsDisabledResponse() {
        when(client.access("10")).thenReturn(
            new OperationsAccess(true, List.of("NOTIFICATION_OPERATOR"), List.of("notification:outbox:replay")),
            new OperationsAccess(true, List.of(), List.of()),
            new OperationsAccess(false, List.of("OPERATIONS_ADMIN"), List.of("operations:roles:manage")),
            new OperationsAccess(false, List.of("OPERATIONS_ADMIN"), List.of("operations:roles:manage"))
        );
        assertThat(provider.getPermissionList("10", "login")).containsExactly("notification:outbox:replay");
        assertThat(provider.getPermissionList("10", "login")).isEmpty();
        assertThat(provider.getPermissionList("10", "login")).isEmpty();
        assertThat(provider.getRoleList("10", "login")).isEmpty();
    }

    @Test
    void dependencyFailureIsNotAnAllowedOrCachedDecision() {
        when(client.access("10")).thenThrow(new OperationsUnavailableException("isolated unavailable"));
        assertThatThrownBy(() -> provider.getPermissionList("10", "login")).isInstanceOf(
            OperationsUnavailableException.class
        );
    }

    @Test
    void defaultConfigurationDoesNotInstallOperationsHttpOrProvider() {
        new ApplicationContextRunner()
            .withUserConfiguration(
                OperationsAccessController.class,
                OperationsPermissionProvider.class,
                OperationsAuthorizationClient.class
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(OperationsAccessController.class);
                assertThat(context).doesNotHaveBean(OperationsPermissionProvider.class);
                assertThat(context).doesNotHaveBean(OperationsAuthorizationClient.class);
            });
    }

    @Test
    void explicitFlagInstallsOperationsComponentsWithoutAutogrant() {
        new ApplicationContextRunner()
            .withPropertyValues("koko.operations.enabled=true")
            .withUserConfiguration(
                OperationsAccessController.class,
                OperationsPermissionProvider.class,
                OperationsAuthorizationClient.class
            )
            .run(context -> {
                assertThat(context).hasSingleBean(OperationsAccessController.class);
                assertThat(context).hasSingleBean(OperationsPermissionProvider.class);
                assertThat(context).hasSingleBean(OperationsAuthorizationClient.class);
            });
    }
}
