package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.api.operations.OutboxOperationsRpcService;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
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
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

/** 真 loopback Triple 网络与生产 Client；领域夹具不证明 Spring/MySQL/Nacos/Redis 已联调。 */
@Timeout(30)
class OutboxTripleTransportTest {

    /** 仅本类拥有的框架，不销毁其他测试的 defaultModel。 */
    private static FrameworkModel framework;
    /** 本类 export，收尾明确 unexport。 */
    private static final List<ServiceConfig<OutboxOperationsRpcService>> services = new ArrayList<>();
    /** 本类网络引用，收尾明确 destroy。 */
    private static final List<ReferenceConfig<OutboxOperationsRpcService>> references = new ArrayList<>();
    /** 固定域夹具，只在本地测试端口注册。 */
    private static final Map<String, DomainFixture> fixtures = new ConcurrentHashMap<>();
    /** 实际 Gateway 适配器，内部引用全部是网络代理，不是 Mockito。 */
    private static OutboxOperationsClient client;
    /** 由系统分配的本机端口，不连接或覆盖线上服务。 */
    private static int port;
    /** 合成事件，非生产标识。 */
    private static final String EVENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    /** 保留六位小数的契约时间。 */
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 3, 14, 0, 0, 123456000);
    /** 当前 Dubbo 会替换 127.*，使用明确的 IPv6 loopback 并检查实际 bind.ip。 */
    private static final String LOOPBACK = "::1";

    @BeforeAll
    static void start() throws Exception {
        assertThat(InetAddress.getByName(LOOPBACK).isLoopbackAddress()).isTrue();
        try (var available = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            port = available.getLocalPort();
        }
        framework = new FrameworkModel();
        var provider = framework.newApplication();
        var consumer = framework.newApplication();
        var application = new ApplicationConfig("koko-operations-triple-isolated-provider");
        application.setQosEnable(false);
        application.setCheckSerializable(true);
        application.setSerializeCheckStatus("STRICT");
        var consumerApplication = new ApplicationConfig("koko-operations-triple-isolated-consumer");
        consumerApplication.setQosEnable(false);
        consumerApplication.setCheckSerializable(true);
        consumerApplication.setSerializeCheckStatus("STRICT");
        var protocol = new ProtocolConfig("tri", port);
        protocol.setHost(LOOPBACK);
        protocol.setThreads(4);
        client = new OutboxOperationsClient();
        for (String domain : List.of("identity", "community", "live")) {
            var fixture = new DomainFixture(domain);
            fixtures.put(domain, fixture);
            var service = new ServiceConfig<OutboxOperationsRpcService>(provider.getDefaultModule());
            services.add(service);
            service.setApplication(application);
            service.setRegistry(new RegistryConfig("N/A"));
            service.setProtocols(List.of(protocol));
            service.setInterface(OutboxOperationsRpcService.class);
            service.setGroup(domain);
            service.setVersion("1.0.0");
            service.setRef(fixture);
            service.export();
            assertThat(service.getExportedUrls())
                .isNotEmpty()
                .allSatisfy(url -> assertThat(url.getParameter("bind.ip")).isEqualTo(LOOPBACK));
            var reference = new ReferenceConfig<OutboxOperationsRpcService>(consumer.getDefaultModule());
            references.add(reference);
            reference.setApplication(consumerApplication);
            reference.setRegistry(new RegistryConfig("N/A"));
            reference.setInterface(OutboxOperationsRpcService.class);
            reference.setGroup(domain);
            reference.setVersion("1.0.0");
            reference.setUrl("tri://[" + LOOPBACK + "]:" + port);
            reference.setInjvm(false);
            reference.setTimeout(1000);
            reference.setRetries(0);
            ReflectionTestUtils.setField(client, domain, reference.get());
        }
    }

    @AfterAll
    static void stop() {
        fixtures.values().forEach(fixture -> fixture.release.countDown());
        references.forEach(ReferenceConfig::destroy);
        services.forEach(ServiceConfig::unexport);
        if (framework != null) framework.destroy();
    }

    @Test
    void threeGroupsReachDistinctRemoteProvidersAndPreserveCursorAndPrecision() {
        var cursor = new OutboxEventCursor(TIME, EVENT);
        for (String domain : List.of("identity", "community", "live")) {
            var page = client.dead(domain, "10", cursor, 20);
            assertThat(page.items().getFirst().summary()).isEqualTo(domain);
            assertThat(page.items().getFirst().recipientId()).isEqualTo("9007199254740993");
            assertThat(page.items().getFirst().totalAttempts()).isEqualTo("9007199254740995");
            assertThat(page.nextCursor()).isEqualTo(cursor);
            assertThat(fixtures.get(domain).thread).doesNotContain("main");
        }
    }

    @Test
    void declaredDenialMissingResourceAndConflictRemainBusinessExceptionsAcrossNetwork() {
        assertThatThrownBy(() -> client.dead("identity", "20", null, 20)).isInstanceOf(
            OperationsAccessDeniedException.class
        );
        assertThatThrownBy(() -> client.receipt("community", "10", EVENT, "unknown")).isInstanceOf(
            OperationsNotFoundException.class
        );
        assertThatThrownBy(() ->
            client.replay(
                "live",
                "10",
                "s".repeat(64),
                new OutboxReplayCommand("conflict", EVENT, 9, "隔离网络契约测试原因"),
                "a".repeat(43)
            )
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void auditListsAndAcceptedReceiptRoundTripWithoutInventingDelivery() {
        var command = new OutboxReplayCommand("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", EVENT, 0, "隔离网络契约测试原因");
        var receipt = client.replay("community", "10", "s".repeat(64), command, "a".repeat(43));
        assertThat(receipt.generation()).isEqualTo(1);
        assertThat(receipt.acceptedAt()).isEqualTo(TIME);
        var observed = client.receipt("community", "10", EVENT, command.requestId());
        assertThat(observed.reason()).isEqualTo(command.reason());
        assertThat(client.audits("community", "10", EVENT, null, 10).items()).contains(observed);
        assertThat(client.detail("community", "10", EVENT).status()).isEqualTo("DEAD");
    }

    @Test
    void networkTimeoutDoesNotRetryAndOriginalRequestMayLaterHaveReceipt() throws Exception {
        var fixture = fixtures.get("identity");
        var command = new OutboxReplayCommand(
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
            EVENT,
            0,
            "隔离超时后只能查询原请求"
        );
        int previousCalls = fixture.replayCalls.get();
        assertThatThrownBy(() -> client.replay("identity", "10", "s".repeat(64), command, "a".repeat(43)))
            .isInstanceOf(OperationsUnavailableException.class)
            .hasCause(null);
        assertThat(fixture.entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.replayCalls.get()).isEqualTo(previousCalls + 1);
        fixture.release.countDown();
        assertThat(fixture.finished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(client.receipt("identity", "10", EVENT, command.requestId()).requestId()).isEqualTo(
            command.requestId()
        );
        assertThat(fixture.replayCalls.get()).isEqualTo(previousCalls + 1);
    }

    /** 仅 transport 夹具，没有真实权限、持久化或消费副作用，不充当业务实现。 */
    static final class DomainFixture implements OutboxOperationsRpcService {

        /** 证明固定 group 路由不同目标。 */
        private final String domain;
        /** 夹具事实，不冒称生产持久化。 */
        private final Map<String, OutboxAuditView> receipts = new ConcurrentHashMap<>();
        /** 网络重复调用计数。 */
        private final AtomicInteger replayCalls = new AtomicInteger();
        /** 明确观察处理已开始。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 测试控制的超时窗口。 */
        private final CountDownLatch release = new CountDownLatch(1);
        /** 证明超时后处理继续完成。 */
        private final CountDownLatch finished = new CountDownLatch(1);
        /** 实际远程执行线程。 */
        private volatile String thread = "";

        DomainFixture(String domain) {
            this.domain = domain;
        }

        @Override
        public OutboxDeadPage dead(String actor, OutboxEventCursor cursor, int limit) {
            if (!"10".equals(actor)) throw new OperationsAccessDeniedException("isolated denial");
            thread = Thread.currentThread().getName();
            return new OutboxDeadPage(List.of(detail(actor, EVENT)), cursor);
        }

        @Override
        public OutboxEventView detail(String actor, String eventId) {
            return new OutboxEventView(
                EVENT,
                "FOLLOW",
                "9007199254740993",
                "10",
                "resource",
                domain,
                "DEAD",
                10,
                "9007199254740995",
                0,
                null,
                TIME,
                TIME,
                null
            );
        }

        @Override
        public OutboxAuditPage audits(String actor, String eventId, Long before, int limit) {
            return new OutboxAuditPage(List.copyOf(receipts.values()), null);
        }

        @Override
        public OutboxAuditView receipt(String actor, String eventId, String requestId) {
            var result = receipts.get(requestId);
            if (result == null) throw new OperationsNotFoundException("isolated missing receipt");
            return result;
        }

        @Override
        public OutboxReplayReceipt replay(String actor, String session, OutboxReplayCommand command, String proof) {
            if (command.expectedGeneration() != 0) throw new IllegalStateException("isolated generation conflict");
            replayCalls.incrementAndGet();
            if (command.requestId().startsWith("cccccccc")) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("isolated request interrupted");
                }
            }
            receipts.put(
                command.requestId(),
                new OutboxAuditView(command.requestId(), EVENT, actor, 0, 1, command.reason(), 10, "10", null, TIME)
            );
            finished.countDown();
            return new OutboxReplayReceipt(command.requestId(), EVENT, 1, TIME);
        }
    }
}
