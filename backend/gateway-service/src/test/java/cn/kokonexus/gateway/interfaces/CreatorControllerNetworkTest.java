package cn.kokonexus.gateway.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.identity.CreatorPage;
import cn.kokonexus.api.identity.CreatorProfile;
import cn.kokonexus.api.identity.IdentityRpcService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.http.server.HttpServer;

/** 实际WebFlux路径选择和HTTP TCP；RPC投影是明确夹具，不代替真实资料/权限数据库。 */
class CreatorControllerNetworkTest {

    @Configuration
    @EnableWebFlux
    static class HttpConfiguration {}

    @Test
    void reservedSlugUsesDetailNamespaceWhilePageAndLegacyMappingsRemainCompatible() throws Exception {
        var rpc = mock(IdentityRpcService.class);
        var profile = new CreatorProfile(
            "12",
            "page",
            "公开作者",
            "",
            "完整介绍",
            null,
            null,
            null,
            null,
            "ACTIVE",
            1,
            0
        );
        when(rpc.findPublishedCreator("page")).thenReturn(profile);
        when(rpc.findPublishedCreator("legacy-author")).thenReturn(
            new CreatorProfile("13", "legacy-author", "旧路径", "", "", null, null, null, null, "ACTIVE", 0, 0)
        );
        when(rpc.pagePublishedCreators(1, 12)).thenReturn(new CreatorPage(List.of(profile), 1, 12, 1));
        try (var context = new AnnotationConfigApplicationContext(); var browser = HttpClient.newHttpClient()) {
            context.register(HttpConfiguration.class);
            context.registerBean(
                org.springframework.validation.beanvalidation.MethodValidationPostProcessor.class,
                () -> {
                    var validator = new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
                    validator.setProxyTargetClass(true);
                    return validator;
                }
            );
            context.registerBean(CreatorController.class, () -> {
                var controller = new CreatorController();
                ReflectionTestUtils.setField(controller, "identityRpcService", rpc);
                return controller;
            });
            context.registerBean(GatewayExceptionHandler.class);
            context.refresh();
            var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build()))
                .bindNow();
            try {
                String base = "http://127.0.0.1:" + server.port();
                var detail = get(browser, base, "/api/discovery/creators/by-slug/page");
                assertThat(detail.statusCode()).isEqualTo(200);
                assertThat(detail.body())
                    .contains("\"slug\":\"page\"", "\"bio\":\"完整介绍\"")
                    .doesNotContain("\"items\"");
                var page = get(browser, base, "/api/discovery/creators/page?page=1&size=12");
                assertThat(page.statusCode()).isEqualTo(200);
                assertThat(page.body()).contains("\"items\"", "\"total\":1");
                assertThat(get(browser, base, "/api/discovery/creators/legacy-author").statusCode()).isEqualTo(200);
                var missing = get(browser, base, "/api/discovery/creators/by-slug/draft-author");
                assertThat(missing.statusCode()).isEqualTo(404);
                assertThat(missing.body()).contains("NOT_FOUND").doesNotContain("完整介绍");
                var invalid = get(browser, base, "/api/discovery/creators/by-slug/InvalidSlug");
                assertThat(invalid.statusCode()).isEqualTo(400);
                assertThat(invalid.body())
                    .contains("INVALID_INPUT")
                    .doesNotContain("InvalidSlug", "ConstraintViolation");
                verify(rpc).findPublishedCreator("page");
                verify(rpc).pagePublishedCreators(1, 12);
                verify(rpc).findPublishedCreator("legacy-author");
                verify(rpc).findPublishedCreator("draft-author");
                verifyNoMoreInteractions(rpc);
            } finally {
                server.disposeNow();
            }
        }
    }

    private HttpResponse<String> get(HttpClient browser, String base, String path) throws Exception {
        return browser.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }
}
