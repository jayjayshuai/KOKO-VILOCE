package cn.kokonexus.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.WebFilter;

class ApiRateLimitFilterTest {

    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(null, null);

    @Test
    void appliesToGatewayControllersAndProxiedRoutes() {
        assertThat(filter).isInstanceOf(WebFilter.class);
    }

    @Test
    void authenticationEndpointsUseStrictIpBucket() {
        var policy = filter.resolvePolicy(MockServerHttpRequest.post("/api/auth/login").build());

        assertThat(policy.bucket()).isEqualTo("auth");
        assertThat(policy.capacity()).isEqualTo(10);
        assertThat(policy.failClosed()).isTrue();
    }

    @Test
    void mutationsUseProtectedWriteBucket() {
        var policy = filter.resolvePolicy(MockServerHttpRequest.method(HttpMethod.PUT, "/api/communities/1").build());

        assertThat(policy.bucket()).isEqualTo("write");
        assertThat(policy.capacity()).isEqualTo(60);
        assertThat(policy.failClosed()).isTrue();
    }

    @Test
    void publicReadsUseHigherCapacityFailOpenBucket() {
        var policy = filter.resolvePolicy(MockServerHttpRequest.get("/api/discovery/communities").build());

        assertThat(policy.bucket()).isEqualTo("read");
        assertThat(policy.capacity()).isEqualTo(240);
        assertThat(policy.failClosed()).isFalse();
    }
}
