package cn.kokonexus.gateway.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.identity.*;
import cn.kokonexus.gateway.infrastructure.MediaAdmissionClient;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.*;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.*;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.http.server.HttpServer;

/** 实际环回Triple STRICT与HTTP TCP/生产Controller；注册领域为明确冲突夹具，不冒充MySQL证明。 */
@Timeout(40)
class RegistrationTripleHttpTest {

    /** 只启用真实WebFlux映射，不扫描/连接外部Redis/Nacos。 */
    @Configuration
    @EnableWebFlux
    static class HttpConfiguration {}

    @Test
    void conflictCrossesStrictTripleAndReturns409WhileInvalidBodyNeverCallsRpc() throws Exception {
        int port;
        try (var available = new ServerSocket(0, 1, InetAddress.getByName("::1"))) {
            port = available.getLocalPort();
        }
        var framework = new FrameworkModel();
        var provider = framework.newApplication();
        var consumer = framework.newApplication();
        var exported = new ServiceConfig<IdentityRpcService>(provider.getDefaultModule());
        var reference = new ReferenceConfig<IdentityRpcService>(consumer.getDefaultModule());
        var calls = new AtomicInteger();
        var strategy = cn.dev33.satoken.strategy.SaStrategy.instance;
        var previousRequest = strategy.createSaRequest;
        var previousResponse = strategy.createSaResponse;
        var previousStorage = strategy.createSaStorage;
        var previousMatcher = strategy.routeMatcher;
        new cn.dev33.satoken.reactor.spring.SaTokenContextRegister();
        try {
            var application = new ApplicationConfig("isolated-registration-provider");
            application.setQosEnable(false);
            application.setCheckSerializable(true);
            application.setSerializeCheckStatus("STRICT");
            var protocol = new ProtocolConfig("tri", port);
            protocol.setHost("::1");
            protocol.setThreads(2);
            exported.setApplication(application);
            exported.setRegistry(new RegistryConfig("N/A"));
            exported.setProtocol(protocol);
            exported.setInterface(IdentityRpcService.class);
            exported.setVersion("1.0.0");
            exported.setRef(
                (IdentityRpcService) java.lang.reflect.Proxy.newProxyInstance(
                    IdentityRpcService.class.getClassLoader(),
                    new Class[] { IdentityRpcService.class },
                    (object, method, args) -> {
                        if (method.getName().equals("register")) {
                            calls.incrementAndGet();
                            throw new RegistrationConflictException();
                        }
                        if (method.getName().equals("toString")) return "IsolatedRegistrationFixture";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                        if (method.getName().equals("equals")) return object == args[0];
                        throw new UnsupportedOperationException("Unused isolated fixture method");
                    }
                )
            );
            exported.export();
            var caller = new ApplicationConfig("isolated-registration-consumer");
            caller.setQosEnable(false);
            caller.setCheckSerializable(true);
            caller.setSerializeCheckStatus("STRICT");
            reference.setApplication(caller);
            reference.setRegistry(new RegistryConfig("N/A"));
            reference.setInterface(IdentityRpcService.class);
            reference.setVersion("1.0.0");
            reference.setUrl("tri://[::1]:" + port);
            reference.setInjvm(false);
            reference.setRetries(0);
            reference.setTimeout(3000);
            var rpc = reference.get();
            assertThatThrownBy(() ->
                rpc.register(
                    new RegisterIdentityCommand(
                        "duplicate@example.invalid",
                        "synthetic-password-value",
                        "synthetic_user",
                        "合成用户"
                    )
                )
            )
                .isInstanceOf(RegistrationConflictException.class)
                .hasNoCause();
            try (var context = new AnnotationConfigApplicationContext(); var browser = HttpClient.newHttpClient()) {
                context.register(HttpConfiguration.class);
                context.registerBean(MediaAdmissionClient.class, () -> mock(MediaAdmissionClient.class));
                context.registerBean(AuthController.class, () -> {
                    var controller = new AuthController();
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
                    var uri = URI.create("http://127.0.0.1:" + server.port() + "/api/auth/register");
                    String body =
                        "{\"email\":\"duplicate@example.invalid\",\"password\":\"synthetic-password-value\",\"handle\":\"synthetic_user\",\"displayName\":\"合成用户\"}";
                    var response = browser.send(
                        HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    );
                    assertThat(response.statusCode()).isEqualTo(409);
                    assertThat(response.body())
                        .contains("REGISTRATION_CONFLICT", "已被注册")
                        .doesNotContain("synthetic-password-value", "duplicate@example.invalid", "Exception");
                    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
                    int before = calls.get();
                    var invalid = browser.send(
                        HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .POST(
                                HttpRequest.BodyPublishers.ofString(body.replace("synthetic-password-value", "short"))
                            )
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    );
                    assertThat(invalid.statusCode()).isEqualTo(400);
                    assertThat(invalid.body())
                        .contains("INVALID_INPUT")
                        .doesNotContain("short", "duplicate@example.invalid", "rejected value");
                    assertThat(calls.get()).isEqualTo(before);
                } finally {
                    server.disposeNow();
                }
            }
        } finally {
            reference.destroy();
            exported.unexport();
            framework.destroy();
            strategy.createSaRequest = previousRequest;
            strategy.createSaResponse = previousResponse;
            strategy.createSaStorage = previousStorage;
            strategy.routeMatcher = previousMatcher;
        }
    }
}
