package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxOperationsRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxOperationsClientTest {

    @Test
    void onlyThreeFixedReferencesAreSelectable() throws Exception {
        var client = new OutboxOperationsClient();
        for (String domain : java.util.List.of("identity", "community", "live")) {
            var service = mock(OutboxOperationsRpcService.class);
            ReflectionTestUtils.setField(client, domain, service);
            client.dead(domain, "10", null, 20);
            verify(service).dead("10", null, 20);
            var reference = OutboxOperationsClient.class.getDeclaredField(domain).getAnnotation(DubboReference.class);
            assertThat(reference.group()).isEqualTo(domain);
            assertThat(reference.retries()).isZero();
        }
        assertThatThrownBy(() -> client.dead("../../identity", "10", null, 20)).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    @Test
    void rpcTimeoutIsAmbiguousNotPermissionOrSentSuccess() {
        var client = new OutboxOperationsClient();
        var service = mock(OutboxOperationsRpcService.class);
        ReflectionTestUtils.setField(client, "identity", service);
        when(service.dead("10", null, 20)).thenThrow(new RpcException("secret invocation"));
        assertThatThrownBy(() -> client.dead("identity", "10", null, 20))
            .isInstanceOf(OperationsUnavailableException.class)
            .hasNoCause()
            .hasMessageNotContaining("secret invocation");
    }
}
