package cn.kokonexus.gateway.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void invalidCredentialsReturnUnauthorizedWithoutInternalError() {
        var exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login").header("X-Request-Id", "login-test-trace")
        );

        var response = handler.authenticationFailed(new AuthenticationFailedException(), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(response.getBody().traceId()).isEqualTo("login-test-trace");
    }

    @Test
    void missingCreatorProfileReturnsNotFound() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/creators/me"));

        var response = handler.notFound(new GatewayResourceNotFoundException("尚未创建创作者主页"), exchange);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void passwordConfirmationFailureDoesNotExpireLoginOrExposeDetail() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/operations/role-changes"));
        var response = handler.operationsForbidden(
            new cn.kokonexus.api.operations.OperationsAccessDeniedException("fixture-sensitive-detail"),
            exchange
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("OPERATIONS_FORBIDDEN");
        assertThat(response.getBody().message()).doesNotContain("fixture-sensitive-detail");
    }

    @Test
    void quotaFailureAndDependencyFailureHaveDistinctRetrySemantics() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/operations/replay-confirmations"));
        var limited = handler.operationsRateLimited(
            new cn.kokonexus.api.operations.OperationsRateLimitedException(),
            exchange
        );
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        var unavailable = handler.operationsUnavailable(
            new cn.kokonexus.api.operations.OperationsUnavailableException("isolated unavailable"),
            exchange
        );
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody().code()).isEqualTo("OPERATIONS_UNAVAILABLE");
    }
}
