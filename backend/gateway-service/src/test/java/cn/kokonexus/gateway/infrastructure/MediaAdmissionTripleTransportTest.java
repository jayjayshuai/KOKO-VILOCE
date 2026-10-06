package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.*;

import cn.kokonexus.api.voice.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.*;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

/** 实际环回Triple/生产Client/可序列化脱敏请求；准入业务为明确夹具，不证明身份/SQL/SFU。 */
@Timeout(30)
class MediaAdmissionTripleTransportTest {

    /** 本测试私有框架，不销毁其他应用或本地调试服务。 */ private FrameworkModel framework;
    /** 本轮精确注册/引用资源，结束时关闭。 */ private ServiceConfig<MediaAdmissionRpcService> exported;
    /** 本轮实际网络引用。 */ private ReferenceConfig<MediaAdmissionRpcService> reference;
    /** 证明拒绝、故障和超时均不重试。 */ private final AtomicInteger calls = new AtomicInteger();
    /** 实际生产网络适配器。 */ private MediaAdmissionClient client;

    @BeforeEach
    void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("::1"))) {
            port = socket.getLocalPort();
        }
        framework = new FrameworkModel();
        var provider = framework.newApplication();
        var consumer = framework.newApplication();
        var app = new ApplicationConfig("isolated-media-admission-provider");
        app.setQosEnable(false);
        app.setSerializeCheckStatus("STRICT");
        app.setCheckSerializable(true);
        var protocol = new ProtocolConfig("tri", port);
        protocol.setHost("::1");
        protocol.setThreads(2);
        exported = new ServiceConfig<>(provider.getDefaultModule());
        exported.setApplication(app);
        exported.setRegistry(new RegistryConfig("N/A"));
        exported.setProtocol(protocol);
        exported.setInterface(MediaAdmissionRpcService.class);
        exported.setVersion("1.0.0");
        exported.setRef(command -> {
            calls.incrementAndGet();
            if (command.token().equals("synthetic-failure")) throw new MediaAdmissionUnavailableException();
            if (command.token().equals("synthetic-slow")) try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MediaAdmissionUnavailableException();
            }
            return "42".equals(command.userId()) && "synthetic-allowed".equals(command.token());
        });
        exported.export();
        var caller = new ApplicationConfig("isolated-media-admission-consumer");
        caller.setQosEnable(false);
        caller.setSerializeCheckStatus("STRICT");
        caller.setCheckSerializable(true);
        reference = new ReferenceConfig<>(consumer.getDefaultModule());
        reference.setApplication(caller);
        reference.setRegistry(new RegistryConfig("N/A"));
        reference.setInterface(MediaAdmissionRpcService.class);
        reference.setVersion("1.0.0");
        reference.setUrl("tri://[::1]:" + port);
        reference.setInjvm(false);
        reference.setRetries(0);
        reference.setTimeout(150);
        client = new MediaAdmissionClient();
        ReflectionTestUtils.setField(client, "service", reference.get());
    }

    @AfterEach
    void stop() {
        if (reference != null) reference.destroy();
        if (exported != null) exported.unexport();
        if (framework != null) framework.destroy();
    }

    @Test
    void actualWirePreservesIdentityAndCredentialWhileDenialAndUnavailableNeverBecomeAllowed() {
        assertThat(client.admit(new MediaAdmissionCommand("42", "synthetic-allowed"))).isTrue();
        assertThat(client.admit(new MediaAdmissionCommand("43", "synthetic-allowed"))).isFalse();
        assertThat(client.admit(new MediaAdmissionCommand("42", "synthetic-denied"))).isFalse();
        assertThatThrownBy(() -> client.admit(new MediaAdmissionCommand("42", "synthetic-failure")))
            .isInstanceOf(MediaAdmissionUnavailableException.class)
            .hasNoCause();
        int before = calls.get();
        assertThatThrownBy(() -> client.admit(new MediaAdmissionCommand("42", "synthetic-slow")))
            .isInstanceOf(MediaAdmissionUnavailableException.class)
            .hasNoCause();
        assertThat(calls.get()).isEqualTo(before + 1);
        assertThat(new MediaAdmissionCommand("42", "synthetic-allowed").toString()).doesNotContain("synthetic-allowed");
    }
}
