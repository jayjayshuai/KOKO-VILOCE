package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseAuthorizationRpcService;
import cn.kokonexus.api.operations.BindingReleaseRecoveryRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsRateLimitedException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.ReplayConfirmation;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

/** 生产Client、新恢复契约与两跳真Triple；领域夹具不证明Boot/Nacos/Redis/MySQL联调。 */
@Timeout(30)
class BindingRecoveryTripleTransportTest {

    /** 本类拥有的框架，不使用或销毁其他测试defaultModel。 */
    private static FrameworkModel framework;
    /** 本类导出与引用，全部显式回收。 */
    private static final List<ServiceConfig<?>> SERVICES = new ArrayList<>();
    /** 禁止injvm的实际网络引用。 */
    private static final List<ReferenceConfig<?>> REFERENCES = new ArrayList<>();
    /** 固定两域隔离夹具。 */
    private static final Map<String, DomainFixture> FIXTURES = new ConcurrentHashMap<>();
    /** 每个应用只配置同一ApplicationConfig，避免第二跳改变Provider身份。 */
    private static final Map<ApplicationModel, ApplicationConfig> APPLICATIONS = new ConcurrentHashMap<>();
    /** 生产网关Client。 */
    private static BindingReleaseRecoveryClient client;
    /** 动态分配的IPv6环回端口。 */
    private static int port;
    /** 合成目标，不接生产账号或任务。 */
    private static final String TASK = "81000000-0000-4000-8000-000000000001";
    /** 保留微秒的数据库时间契约。 */
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 5, 1, 0, 0, 123456000);
    /** 固定隔离秘密，不是真实身份凭据。 */
    private static final String PROOF = "a".repeat(43);
    /** 会话合成摘要。 */
    private static final String SESSION = "b".repeat(64);

    @BeforeAll
    static void start() throws Exception {
        var loopback = InetAddress.getByName("::1");
        assertThat(loopback.isLoopbackAddress()).isTrue();
        try (var available = new ServerSocket(0, 1, loopback)) {
            port = available.getLocalPort();
        }
        framework = new FrameworkModel();
        var provider = framework.newApplication();
        var consumer = framework.newApplication();
        APPLICATIONS.put(provider, application("binding-recovery-isolated-provider"));
        APPLICATIONS.put(consumer, application("binding-recovery-isolated-consumer"));
        var protocol = new ProtocolConfig("tri", port);
        protocol.setHost("::1");
        protocol.setThreads(4);
        export(provider, protocol, BindingReleaseAuthorizationRpcService.class, null, new ConfirmationFixture());
        // 领域Provider也必须经网络走独立确认接口，而不是调用同进程对象。
        var secondHop = reference(provider, BindingReleaseAuthorizationRpcService.class, null, 3000);
        client = new BindingReleaseRecoveryClient();
        ReflectionTestUtils.setField(
            client,
            "confirmations",
            reference(consumer, BindingReleaseAuthorizationRpcService.class, null, 3000)
        );
        for (String domain : List.of("identity", "community")) {
            var fixture = new DomainFixture(domain, secondHop);
            FIXTURES.put(domain, fixture);
            export(provider, protocol, BindingReleaseRecoveryRpcService.class, domain, fixture);
            ReflectionTestUtils.setField(
                client,
                domain,
                reference(consumer, BindingReleaseRecoveryRpcService.class, domain, 1000)
            );
        }
    }

    private static ApplicationConfig application(String name) {
        var config = new ApplicationConfig(name);
        config.setQosEnable(false);
        config.setCheckSerializable(true);
        config.setSerializeCheckStatus("STRICT");
        return config;
    }

    private static <T> void export(
        ApplicationModel owner,
        ProtocolConfig protocol,
        Class<T> type,
        String group,
        T fixture
    ) {
        var config = new ServiceConfig<T>(owner.getDefaultModule());
        SERVICES.add(config);
        config.setApplication(APPLICATIONS.get(owner));
        config.setRegistry(new RegistryConfig("N/A"));
        config.setProtocols(List.of(protocol));
        config.setInterface(type);
        config.setGroup(group);
        config.setVersion("1.0.0");
        config.setRef(fixture);
        config.export();
        assertThat(config.getExportedUrls())
            .isNotEmpty()
            .allSatisfy(url -> assertThat(url.getParameter("bind.ip")).isEqualTo("::1"));
    }

    private static <T> T reference(ApplicationModel owner, Class<T> type, String group, int timeout) {
        var config = new ReferenceConfig<T>(owner.getDefaultModule());
        REFERENCES.add(config);
        config.setApplication(APPLICATIONS.get(owner));
        config.setRegistry(new RegistryConfig("N/A"));
        config.setInterface(type);
        config.setGroup(group);
        config.setVersion("1.0.0");
        config.setUrl("tri://[::1]:" + port);
        config.setInjvm(false);
        config.setTimeout(timeout);
        config.setRetries(0);
        return config.get();
    }

    @AfterAll
    static void stop() {
        FIXTURES.values().forEach(fixture -> fixture.release.countDown());
        REFERENCES.forEach(ReferenceConfig::destroy);
        SERVICES.forEach(ServiceConfig::unexport);
        if (framework != null) framework.destroy();
    }

    @Test
    void dedicatedConfirmationAndBothGroupsRoundTripAllNewRecordsWithRealSecondHop() {
        var command = command("81000000-0000-4000-8000-000000000002", 0);
        for (String domain : List.of("identity", "community")) {
            int previous = FIXTURES.get(domain).validations.get();
            var proof = client.confirm(domain, "10", SESSION, command, "isolated-password");
            assertThat(proof.confirmationToken()).isEqualTo(PROOF);
            var receipt = client.replay(domain, "10", SESSION, command, proof.confirmationToken());
            assertThat(receipt.acceptedGeneration()).isEqualTo(1);
            assertThat(receipt.acceptedAt()).isEqualTo(TIME);
            var audit = client.receipt(domain, "10", TASK, command.commandId());
            assertThat(audit.commandId()).isEqualTo(command.commandId());
            assertThat(audit.reason()).isEqualTo(command.reason());
            assertThat(audit.previousAttempts()).isEqualTo(10);
            assertThat(client.audits(domain, "10", TASK, null, 5).items()).contains(audit);
            assertThat(FIXTURES.get(domain).thread).doesNotContain("main");
            assertThat(FIXTURES.get(domain).validations.get()).isEqualTo(previous + 1);
        }
    }

    @Test
    void declaredBusinessExceptionsAndFixedDomainSurviveNetwork() {
        var command = command("81000000-0000-4000-8000-000000000003", 0);
        assertThatThrownBy(() -> client.confirm("identity", "20", SESSION, command, "isolated-password")).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        assertThatThrownBy(() -> client.confirm("identity", "30", SESSION, command, "isolated-password")).isInstanceOf(
            OperationsRateLimitedException.class
        );
        assertThatThrownBy(() -> client.replay("community", "20", SESSION, command, PROOF)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        assertThatThrownBy(() ->
            client.replay("identity", "10", SESSION, command(command.commandId(), 1), PROOF)
        ).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.receipt("identity", "10", TASK, "missing")).isInstanceOf(
            OperationsNotFoundException.class
        );
        assertThatThrownBy(() -> client.confirm("live", "10", SESSION, command, "isolated-password")).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    @Test
    void timeoutKeepsOriginalCommandQueryableAndNeverAutomaticallyRetries() throws Exception {
        var fixture = FIXTURES.get("identity");
        var command = command("cccccccc-cccc-cccc-cccc-cccccccccccc", 0);
        int previous = fixture.calls.get();
        assertThatThrownBy(() -> client.replay("identity", "10", SESSION, command, PROOF))
            .isInstanceOf(OperationsUnavailableException.class)
            .hasCause(null);
        assertThat(fixture.entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.calls.get()).isEqualTo(previous + 1);
        fixture.release.countDown();
        assertThat(fixture.finished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(client.receipt("identity", "10", TASK, command.commandId()).commandId()).isEqualTo(
            command.commandId()
        );
        assertThat(fixture.calls.get()).isEqualTo(previous + 1);
    }

    private static BindingReleaseReplayCommand command(String id, int generation) {
        return new BindingReleaseReplayCommand(id, TASK, generation, "隔离网络原命令字段完整测试");
    }

    /** 仅协议夹具，不冒称密码哈希、授权事实或确认表真实实现。 */
    static final class ConfirmationFixture implements BindingReleaseAuthorizationRpcService {

        @Override
        public ReplayConfirmation confirm(
            String actor,
            String session,
            String domain,
            BindingReleaseReplayCommand command,
            String password
        ) {
            if ("30".equals(actor)) throw new OperationsRateLimitedException();
            validate(actor, session, domain, command, PROOF);
            return new ReplayConfirmation(PROOF, TIME.plusMinutes(5));
        }

        @Override
        public void validate(
            String actor,
            String session,
            String domain,
            BindingReleaseReplayCommand command,
            String token
        ) {
            if (
                !"10".equals(actor) ||
                !SESSION.equals(session) ||
                !List.of("identity", "community").contains(domain) ||
                !PROOF.equals(token)
            ) throw new OperationsAccessDeniedException("isolated denial");
        }
    }

    /** 仅传输与超时窗口，不使用MySQL或资产副作用。 */
    static final class DomainFixture implements BindingReleaseRecoveryRpcService {

        /** 固定业务域。 */
        private final String domain;
        /** 实际网络第二跳。 */
        private final BindingReleaseAuthorizationRpcService authorization;
        /** 原命令夹具事实。 */
        private final Map<String, BindingReleaseAuditView> receipts = new ConcurrentHashMap<>();
        /** 网络调用次数。 */
        private final AtomicInteger calls = new AtomicInteger();
        /** 第二跳完成次数。 */
        private final AtomicInteger validations = new AtomicInteger();
        /** 超时窗口三个观察点。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 仅测试释放阻塞。 */
        private final CountDownLatch release = new CountDownLatch(1);
        /** 超时后原结果完成。 */
        private final CountDownLatch finished = new CountDownLatch(1);
        /** 证明不是主线程直接调用。 */
        private volatile String thread = "";

        DomainFixture(String domain, BindingReleaseAuthorizationRpcService authorization) {
            this.domain = domain;
            this.authorization = authorization;
        }

        @Override
        public BindingReleaseReplayReceipt replay(
            String actor,
            String session,
            BindingReleaseReplayCommand command,
            String token
        ) {
            authorization.validate(actor, session, domain, command, token);
            if (command.expectedGeneration() != 0) throw new IllegalStateException("isolated generation conflict");
            validations.incrementAndGet();
            calls.incrementAndGet();
            thread = Thread.currentThread().getName();
            if (command.commandId().startsWith("cccccccc")) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("isolated gate expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("isolated interrupted");
                }
            }
            receipts.put(
                command.commandId(),
                new BindingReleaseAuditView(
                    command.commandId(),
                    TASK,
                    actor,
                    0,
                    1,
                    10,
                    10,
                    null,
                    command.reason(),
                    TIME
                )
            );
            if (command.commandId().startsWith("cccccccc")) finished.countDown();
            return new BindingReleaseReplayReceipt(command.commandId(), TASK, 1, TIME);
        }

        @Override
        public BindingReleaseAuditView receipt(String actor, String request, String command) {
            if (!"10".equals(actor)) throw new OperationsAccessDeniedException("isolated denial");
            var result = receipts.get(command);
            if (result == null || !TASK.equals(request)) throw new OperationsNotFoundException("isolated missing");
            return result;
        }

        @Override
        public BindingReleaseAuditPage audits(String actor, String request, Integer before, int limit) {
            if (!"10".equals(actor)) throw new OperationsAccessDeniedException("isolated denial");
            return new BindingReleaseAuditPage(List.copyOf(receipts.values()), null);
        }
    }
}
