package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsAuthorizationRpcService;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OperationsAuthorizationClientTest {

    @Test
    void networkFailureNeverBecomesPermissionOrLeaksInvocationDetail() {
        var rpc = mock(OperationsAuthorizationRpcService.class);
        var client = new OperationsAuthorizationClient();
        ReflectionTestUtils.setField(client, "service", rpc);
        when(rpc.access("10")).thenThrow(new RpcException("fixture-sensitive-invocation"));
        when(rpc.confirmReplay(any(), any(), any(), any(), any())).thenThrow(
            new RpcException("fixture-sensitive-invocation")
        );
        when(rpc.changeRole(any(), any(), any())).thenThrow(new RpcException("fixture-sensitive-invocation"));
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call : java.util.List.of(
            (org.assertj.core.api.ThrowableAssert.ThrowingCallable) () -> client.access("10"),
            () -> client.confirmReplay("10", "fixture", "identity", null, "fixture-password"),
            () -> client.changeRole("10", null, "fixture-password")
        )) {
            assertThatThrownBy(call)
                .isInstanceOf(OperationsUnavailableException.class)
                .hasNoCause()
                .hasMessageNotContaining("fixture-sensitive-invocation");
        }
    }

    @Test
    void declaredBusinessDenialIsNotWrappedAsNetworkFailure() {
        var rpc = mock(OperationsAuthorizationRpcService.class);
        var client = new OperationsAuthorizationClient();
        ReflectionTestUtils.setField(client, "service", rpc);
        when(rpc.access("10")).thenThrow(new OperationsAccessDeniedException("isolated denied"));
        assertThatThrownBy(() -> client.access("10")).isInstanceOf(OperationsAccessDeniedException.class);
    }
}
