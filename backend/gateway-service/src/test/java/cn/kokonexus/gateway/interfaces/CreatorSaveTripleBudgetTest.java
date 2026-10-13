package cn.kokonexus.gateway.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import cn.kokonexus.api.identity.CreatorProfile;
import cn.kokonexus.api.identity.IdentityRpcService;
import cn.kokonexus.api.identity.SaveCreatorProfileCommand;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 从生产注解读取预算构建真实Triple参考；隔离慢Provider，不连接生产或伪造数据库提交。 */
@Timeout(40)
class CreatorSaveTripleBudgetTest {

    @Test
    void onlySaveHasNestedCallBudgetAndRealSixSecondReplyDoesNotCauseDuplicateRpc() throws Exception {
        var annotation = CreatorController.class
            .getDeclaredField("identityRpcService")
            .getAnnotation(DubboReference.class);
        assertThat(annotation.timeout()).isEqualTo(5000);
        assertThat(annotation.retries()).isZero();
        assertThat(annotation.methods()).hasSize(1);
        var setting = annotation.methods()[0];
        assertThat(setting.name()).isEqualTo("saveCreatorProfile");
        assertThat(setting.timeout()).isEqualTo(15000);
        assertThat(setting.retries()).isZero();
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
        try {
            var application = new ApplicationConfig("isolated-creator-budget-provider");
            application.setQosEnable(false);
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
                        if (method.getName().equals("saveCreatorProfile")) {
                            calls.incrementAndGet();
                            Thread.sleep(6000);
                            return new CreatorProfile(
                                "12",
                                "page",
                                "合成慢保存",
                                "",
                                "",
                                null,
                                null,
                                null,
                                null,
                                "DRAFT",
                                0,
                                0
                            );
                        }
                        if (method.getName().equals("toString")) return "IsolatedCreatorBudgetFixture";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                        if (method.getName().equals("equals")) return object == args[0];
                        throw new UnsupportedOperationException("Unused isolated fixture method");
                    }
                )
            );
            exported.export();
            var caller = new ApplicationConfig("isolated-creator-budget-consumer");
            caller.setQosEnable(false);
            caller.setSerializeCheckStatus("STRICT");
            reference.setApplication(caller);
            reference.setRegistry(new RegistryConfig("N/A"));
            reference.setInterface(IdentityRpcService.class);
            reference.setVersion(annotation.version());
            reference.setUrl("tri://[::1]:" + port);
            reference.setInjvm(false);
            reference.setTimeout(annotation.timeout());
            reference.setRetries(annotation.retries());
            var method = new MethodConfig();
            method.setName(setting.name());
            method.setTimeout(setting.timeout());
            method.setRetries(setting.retries());
            reference.setMethods(List.of(method));
            var rpc = reference.get();
            var value = rpc.saveCreatorProfile(
                "12",
                new SaveCreatorProfileCommand("page", "合成慢保存", "", "", null, null, null, null, 0)
            );
            assertThat(value.status()).isEqualTo("DRAFT");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            reference.destroy();
            exported.unexport();
            framework.destroy();
        }
    }
}
