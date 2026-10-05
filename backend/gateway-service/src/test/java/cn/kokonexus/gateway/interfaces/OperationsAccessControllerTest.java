package cn.kokonexus.gateway.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.fun.strategy.SaCreateSaRequestFunction;
import cn.dev33.satoken.fun.strategy.SaCreateSaResponseFunction;
import cn.dev33.satoken.fun.strategy.SaCreateSaStorageFunction;
import cn.dev33.satoken.fun.strategy.SaRouteMatchFunction;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.filter.SaTokenContextFilterForReactor;
import cn.dev33.satoken.reactor.spring.SaTokenContextForSpringReactor;
import cn.dev33.satoken.reactor.spring.SaTokenContextRegister;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaStrategy;
import cn.kokonexus.api.operations.BindingReleaseAuthorizationRpcService;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleaseOperationsRpcService;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseRecoveryRpcService;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsAccessDeniedException;
import cn.kokonexus.api.operations.OperationsNotFoundException;
import cn.kokonexus.api.operations.OperationsRateLimitedException;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.api.operations.OperationsUnavailableException;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxOperationsRpcService;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import cn.kokonexus.api.operations.ReplayCommandBinding;
import cn.kokonexus.api.operations.ReplayConfirmation;
import cn.kokonexus.gateway.configuration.OperationsPermissionProvider;
import cn.kokonexus.gateway.configuration.SaTokenGatewayConfiguration;
import cn.kokonexus.gateway.filter.OffloadedSaReactorFilter;
import cn.kokonexus.gateway.filter.OperationsResponsePrivacyFilter;
import cn.kokonexus.gateway.infrastructure.BindingReleaseOperationsClient;
import cn.kokonexus.gateway.infrastructure.BindingReleaseRecoveryClient;
import cn.kokonexus.gateway.infrastructure.OperationsAuthorizationClient;
import cn.kokonexus.gateway.infrastructure.OutboxOperationsClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 真 WebFlux + Sa-Token 认证上下文；RPC 与会话存储为测试边界，不证明 Redis/Nacos 联调。 */
class OperationsAccessControllerTest {

    /** 每测试保存的全局配置，避免污染其他用例。 */
    private SaTokenConfig previousConfig;
    /** 原会话 DAO，测试后恢复。 */
    private SaTokenDao previousDao;
    /** 原上下文，测试后恢复。 */
    private SaTokenContext previousContext;
    /** 原权限 Provider，测试后恢复。 */
    private StpInterface previousProvider;
    /** 原请求创建策略，测试后恢复，不使用默认未实现的 web 适配器。 */
    private SaCreateSaRequestFunction previousRequestFactory;
    /** 原响应创建策略。 */
    private SaCreateSaResponseFunction previousResponseFactory;
    /** 原存储创建策略。 */
    private SaCreateSaStorageFunction previousStorageFactory;
    /** 原路由匹配策略。 */
    private SaRouteMatchFunction previousRouteMatcher;
    /** 本进程隔离内存会话，不连接生产 Redis。 */
    private SaTokenDaoDefaultImpl dao;
    /** 只 mock 跨服务调用，Controller 与 Sa-Token 不 mock。 */
    private OperationsAuthorizationClient client;
    /** 固定三组引用的网络边界桩，真实适配器负责域白名单与错误转换。 */
    private OutboxOperationsRpcService outboxRpc;
    /** 专用绑定释放 RPC 边界，不借通知重放获取权限。 */
    private BindingReleaseOperationsRpcService bindingRpc;
    /** 独立资产恢复 Provider 网络边界。 */
    private BindingReleaseRecoveryRpcService recoveryRpc;
    /** 独立资产动作确认网络边界，不复用通知确认。 */
    private BindingReleaseAuthorizationRpcService recoveryAuthority;
    /** 带真实认证/上下文过滤器的 HTTP 测试客户端。 */
    private WebTestClient http;
    /** 每测试生成的虚拟登录会话，禁止诊断输出。 */
    private String token;
    /** 本用例实际创建的有界鉴权过滤器，避免测试后遗留工作池。 */
    private OffloadedSaReactorFilter authenticationFilter;

    @BeforeEach
    void setUp() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        previousProvider = SaManager.getStpInterface();
        previousRequestFactory = SaStrategy.instance.createSaRequest;
        previousResponseFactory = SaStrategy.instance.createSaResponse;
        previousStorageFactory = SaStrategy.instance.createSaStorage;
        previousRouteMatcher = SaStrategy.instance.routeMatcher;
        new SaTokenContextRegister(); // 与 starter 相同的真实 Reactor 适配器注册。
        dao = new SaTokenDaoDefaultImpl();
        SaManager.setConfig(
            new SaTokenConfig()
                .setTokenName("operations-fixture-token")
                .setIsReadCookie(false)
                .setIsReadHeader(true)
                .setIsPrint(false)
        );
        SaManager.setSaTokenDao(dao);
        SaManager.setSaTokenContext(new SaTokenContextForSpringReactor());
        client = mock(OperationsAuthorizationClient.class);
        when(client.access("10")).thenReturn(
            new OperationsAccess(
                true,
                List.of("OPERATIONS_ADMIN"),
                List.of("operations:roles:manage", "notification:outbox:replay", "notification:outbox:read")
            )
        );
        SaManager.setStpInterface(new OperationsPermissionProvider(client));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
        token = SaReactorSyncHolder.setContext(exchange, () -> {
            StpUtil.login("10");
            return StpUtil.getTokenValue();
        });
        outboxRpc = mock(OutboxOperationsRpcService.class);
        var outboxClient = new OutboxOperationsClient();
        for (String domain : List.of("identity", "community", "live")) {
            ReflectionTestUtils.setField(outboxClient, domain, outboxRpc);
        }
        authenticationFilter = new SaTokenGatewayConfiguration().saReactorFilter();
        bindingRpc = mock(BindingReleaseOperationsRpcService.class);
        var bindingClient = new BindingReleaseOperationsClient();
        for (String domain : List.of("identity", "community")) {
            ReflectionTestUtils.setField(bindingClient, domain, bindingRpc);
        }
        recoveryRpc = mock(BindingReleaseRecoveryRpcService.class);
        recoveryAuthority = mock(BindingReleaseAuthorizationRpcService.class);
        var recoveryClient = new BindingReleaseRecoveryClient();
        ReflectionTestUtils.setField(recoveryClient, "identity", recoveryRpc);
        ReflectionTestUtils.setField(recoveryClient, "community", recoveryRpc);
        ReflectionTestUtils.setField(recoveryClient, "confirmations", recoveryAuthority);
        http = WebTestClient.bindToController(
            new OperationsAccessController(client),
            new BindingReleaseOperationsController(bindingClient),
            new BindingReleaseRecoveryController(recoveryClient),
            new OutboxOperationsController(outboxClient)
        )
            .controllerAdvice(new GatewayExceptionHandler())
            .webFilter(
                new OperationsResponsePrivacyFilter(),
                new SaTokenContextFilterForReactor(),
                authenticationFilter
            )
            .build();
    }

    @AfterEach
    void tearDown() {
        authenticationFilter.close();
        dao.destroy();
        SaManager.setStpInterface(previousProvider);
        SaManager.setSaTokenContext(previousContext);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setConfig(previousConfig);
        SaStrategy.instance.createSaRequest = previousRequestFactory;
        SaStrategy.instance.createSaResponse = previousResponseFactory;
        SaStrategy.instance.createSaStorage = previousStorageFactory;
        SaStrategy.instance.routeMatcher = previousRouteMatcher;
    }

    @Test
    void bindingReadRequiresSeparatePermissionEvenForNotificationAdmin() {
        http.get()
            .uri("/api/operations/binding-releases/identity/dead")
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store");
        http.get()
            .uri("/api/operations/binding-releases/identity/dead")
            .header("operations-fixture-token", token)
            .exchange()
            .expectStatus()
            .isForbidden();
        verifyNoInteractions(bindingRpc);
    }

    @Test
    void bindingReadPreservesMicrosecondsCurrentActorAndNoStoreWithoutSecrets() {
        when(client.access("10")).thenReturn(
            new OperationsAccess(true, List.of("ASSET_BINDING_AUDITOR"), List.of("asset:binding:read"))
        );
        String request = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        var time = LocalDateTime.of(2026, 10, 4, 12, 0, 0, 123456000);
        when(bindingRpc.dead("10", new BindingReleaseCursor(time, request), 20)).thenReturn(
            new BindingReleasePage(List.of(), null)
        );
        http.get()
            .uri(
                "/api/operations/binding-releases/community/dead?beforeCreatedAt=" +
                    time +
                    "&beforeRequestId=" +
                    request
            )
            .header("operations-fixture-token", token)
            .header("X-Koko-User-Id", "999")
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.items")
            .isEmpty();
        verify(bindingRpc).dead("10", new BindingReleaseCursor(time, request), 20);
        when(bindingRpc.snapshot("10")).thenReturn(new BindingReleaseSnapshot(1001, 0, 0, 1001, 0, 0, time));
        http.get()
            .uri("/api/operations/binding-releases/identity/snapshot")
            .header("operations-fixture-token", token)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.pending")
            .isEqualTo(1001)
            .jsonPath("$.leaseToken")
            .doesNotExist()
            .jsonPath("$.ownerId")
            .doesNotExist();
    }

    @Test
    void bindingReadInvalidDomainCursorAndRpcOutageNeverReturnFakeEmptySuccess() {
        when(client.access("10")).thenReturn(new OperationsAccess(true, List.of(), List.of("asset:binding:read")));
        for (String path : List.of("live/dead", "identity/dead?limit=51", "identity/dead?beforeRequestId=missing")) {
            http.get()
                .uri("/api/operations/binding-releases/" + path)
                .header("operations-fixture-token", token)
                .exchange()
                .expectStatus()
                .isBadRequest();
        }
        verifyNoInteractions(bindingRpc);
        when(bindingRpc.snapshot("10")).thenThrow(new org.apache.dubbo.rpc.RpcException("private-node"));
        http.get()
            .uri("/api/operations/binding-releases/identity/snapshot")
            .header("operations-fixture-token", token)
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.message")
            .isEqualTo("运营能力未启用或依赖暂不可用");
    }

    @Test
    void anonymousCannotReadOrConfirmAndSpoofedUserHeaderDoesNotAuthenticate() {
        http.get()
            .uri("/api/operations/access")
            .header("X-Koko-User-Id", "10")
            .exchange()
            .expectStatus()
            .isUnauthorized();
        http.post()
            .uri("/api/operations/replay-confirmations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request())
            .exchange()
            .expectStatus()
            .isUnauthorized();
        verifyNoInteractions(client);
    }

    @Test
    void confirmationBindsActualSessionAndActorOnBlockingWorker() {
        var worker = new AtomicBoolean();
        when(
            client.confirmReplay(
                eq("10"),
                eq(ReplayCommandBinding.sha256(token)),
                eq("identity"),
                any(),
                eq("fixture-password")
            )
        ).thenAnswer(invocation -> {
            worker.set(Thread.currentThread().getName().startsWith("boundedElastic-"));
            return new ReplayConfirmation("a".repeat(43), LocalDateTime.of(2026, 10, 3, 22, 0));
        });
        authenticatedPost("/api/operations/replay-confirmations", request())
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.confirmationToken")
            .isEqualTo("a".repeat(43));
        assertThat(worker.get()).isTrue();
        verify(client).confirmReplay(
            "10",
            ReplayCommandBinding.sha256(token),
            "identity",
            request().command(),
            "fixture-password"
        );
    }

    @Test
    void wrongPasswordIs403AndSameLoginCanStillReadAccess() {
        when(client.confirmReplay(any(), any(), any(), any(), any())).thenThrow(
            new OperationsAccessDeniedException("isolated sensitive detail")
        );
        authenticatedPost("/api/operations/replay-confirmations", request())
            .expectStatus()
            .isForbidden()
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("OPERATIONS_FORBIDDEN");
        http.get()
            .uri("/api/operations/access")
            .header(StpUtil.getTokenName(), token)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(true);
    }

    @Test
    void revokedPermissionCannotReachPasswordRpc() {
        when(client.access("10")).thenReturn(new OperationsAccess(true, List.of(), List.of()));
        authenticatedPost("/api/operations/replay-confirmations", request()).expectStatus().isForbidden();
        verify(client, org.mockito.Mockito.never()).confirmReplay(any(), any(), any(), any(), any());
    }

    @Test
    void quotaHasRetryHeaderAndRoleCommandUsesAuthenticatedOperator() {
        when(client.confirmReplay(any(), any(), any(), any(), any())).thenThrow(new OperationsRateLimitedException());
        authenticatedPost("/api/operations/replay-confirmations", request())
            .expectStatus()
            .isEqualTo(429)
            .expectHeader()
            .valueEquals("Retry-After", "60");
        var command = new OperationsRoleChangeCommand(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "20",
            "NOTIFICATION_OPERATOR",
            true,
            0,
            null,
            "隔离角色赋权原因不少于十字"
        );
        when(client.changeRole("10", command, "fixture-password")).thenReturn(
            new OperationsRoleChangeReceipt(
                command.requestId(),
                "20",
                command.roleCode(),
                "ACTIVE",
                1,
                null,
                LocalDateTime.of(2026, 10, 3, 22, 0)
            )
        );
        var roleRequest = new OperationsAccessController.RoleChangeRequest(command, "fixture-password");
        authenticatedPost("/api/operations/role-changes", roleRequest)
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.userId")
            .isEqualTo("20");
        verify(client).changeRole("10", command, "fixture-password");
        assertThat(roleRequest.toString()).doesNotContain("fixture-password");
    }

    @Test
    void invalidBodyIs400BeforePasswordRpcAndDiagnosticsRedactPassword() {
        var invalid = new OperationsAccessController.ConfirmationRequest(
            "unknown",
            request().command(),
            "fixture-password"
        );
        authenticatedPost("/api/operations/replay-confirmations", invalid).expectStatus().isBadRequest();
        verify(client, org.mockito.Mockito.never()).confirmReplay(any(), any(), any(), any(), any());
        assertThat(invalid.toString()).doesNotContain("fixture-password");
    }

    @Test
    void outboxCompositeCursorPreservesMicrosAndAuthenticatedActorWithoutCaching() {
        var cursor = new OutboxEventCursor(
            LocalDateTime.of(2026, 10, 3, 14, 0, 0, 123456000),
            request().command().eventId()
        );
        when(outboxRpc.dead("10", cursor, 20)).thenReturn(new OutboxDeadPage(List.of(), null));
        authenticatedGet(
            "/api/operations/outbox/live/dead?beforeCreatedAt=2026-10-03T14:00:00.123456" +
                "&beforeEventId=" +
                cursor.eventId()
        )
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectHeader()
            .valueEquals("Pragma", "no-cache");
        verify(outboxRpc).dead("10", cursor, 20);
    }

    @Test
    void malformedCursorLimitsOrUnknownDomainCannotReachOutboxRpc() {
        for (String uri : List.of(
            "identity/dead?beforeEventId=" + request().command().eventId(),
            "identity/dead?limit=0",
            "identity/dead?limit=51",
            "unknown/dead",
            "identity/events/" + request().command().eventId() + "/audits?limit=21",
            "identity/events/" + request().command().eventId() + "/audits?beforeGeneration=12"
        )) {
            authenticatedGet("/api/operations/outbox/" + uri)
                .expectStatus()
                .isBadRequest();
        }
        verifyNoInteractions(outboxRpc);
    }

    @Test
    void replayReturns202WithActualActorAndSessionHashRatherThanClientIdentity() {
        var command = request().command();
        var body = new OutboxOperationsController.ReplayRequest(command, "a".repeat(43));
        when(outboxRpc.replay("10", ReplayCommandBinding.sha256(token), command, body.confirmationToken())).thenReturn(
            new OutboxReplayReceipt(command.requestId(), command.eventId(), 1, LocalDateTime.of(2026, 10, 3, 14, 0))
        );
        authenticatedPost("/api/operations/outbox/community/replays", body)
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.generation")
            .isEqualTo(1);
        verify(outboxRpc).replay("10", ReplayCommandBinding.sha256(token), command, body.confirmationToken());
        assertThat(body.toString()).doesNotContain(body.confirmationToken(), command.reason());
    }

    @Test
    void readOnlyOperatorCannotReplayAndMalformedProofIs400BeforeRpc() {
        var invalid = new OutboxOperationsController.ReplayRequest(request().command(), "bad-proof");
        authenticatedPost("/api/operations/outbox/identity/replays", invalid).expectStatus().isBadRequest();
        when(client.access("10")).thenReturn(
            new OperationsAccess(true, List.of("NOTIFICATION_AUDITOR"), List.of("notification:outbox:read"))
        );
        authenticatedPost(
            "/api/operations/outbox/identity/replays",
            new OutboxOperationsController.ReplayRequest(request().command(), "a".repeat(43))
        )
            .expectStatus()
            .isForbidden()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store");
        verifyNoInteractions(outboxRpc);
    }

    @Test
    void missingReceiptIs404AndRpcFailureIs503NeverAnAcceptedResponse() {
        var command = request().command();
        when(outboxRpc.receipt("10", command.eventId(), command.requestId())).thenThrow(
            new OperationsNotFoundException("isolated row missing")
        );
        authenticatedGet(
            "/api/operations/outbox/identity/events/" + command.eventId() + "/requests/" + command.requestId()
        )
            .expectStatus()
            .isNotFound()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store");
        when(outboxRpc.replay(any(), any(), any(), any())).thenThrow(
            new OperationsUnavailableException("original request may commit")
        );
        authenticatedPost(
            "/api/operations/outbox/identity/replays",
            new OutboxOperationsController.ReplayRequest(command, "a".repeat(43))
        )
            .expectStatus()
            .isEqualTo(503)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("OPERATIONS_UNAVAILABLE");
    }

    private WebTestClient.ResponseSpec authenticatedGet(String uri) {
        return http.get().uri(uri).header(StpUtil.getTokenName(), token).header("X-Koko-User-Id", "999").exchange();
    }

    @Test
    void bindingRecoveryRequiresSeparateWritePermissionAndAnonymousCannotReachPassword() {
        var command = new BindingReleaseReplayCommand(
            request().command().requestId(),
            request().command().eventId(),
            0,
            request().command().reason()
        );
        var body = new BindingReleaseRecoveryController.ConfirmationRequest(command, "fixture-password");
        http.post()
            .uri("/api/operations/binding-releases/identity/confirmations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store");
        authenticatedPost("/api/operations/binding-releases/identity/confirmations", body).expectStatus().isForbidden();
        when(client.access("10")).thenReturn(
            new OperationsAccess(true, List.of("ASSET_BINDING_AUDITOR"), List.of("asset:binding:read"))
        );
        authenticatedPost(
            "/api/operations/binding-releases/identity/replays",
            new BindingReleaseRecoveryController.RecoveryRequest(command, "a".repeat(43))
        )
            .expectStatus()
            .isForbidden();
        verifyNoInteractions(recoveryRpc, recoveryAuthority);
    }

    @Test
    void bindingRecoveryBindsActualActorSessionAndReturns202NotReleaseSuccess() {
        when(client.access("10")).thenReturn(
            new OperationsAccess(
                true,
                List.of("ASSET_BINDING_RECOVERY"),
                List.of("asset:binding:read", "asset:binding:replay")
            )
        );
        var command = new BindingReleaseReplayCommand(
            request().command().requestId(),
            request().command().eventId(),
            0,
            request().command().reason()
        );
        var time = LocalDateTime.of(2026, 10, 5, 1, 0, 0, 123456000);
        when(
            recoveryAuthority.confirm(
                "10",
                ReplayCommandBinding.sha256(token),
                "community",
                command,
                "fixture-password"
            )
        ).thenReturn(new ReplayConfirmation("a".repeat(43), time));
        authenticatedPost(
            "/api/operations/binding-releases/community/confirmations",
            new BindingReleaseRecoveryController.ConfirmationRequest(command, "fixture-password")
        )
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.confirmationToken")
            .isEqualTo("a".repeat(43));
        when(recoveryRpc.replay("10", ReplayCommandBinding.sha256(token), command, "a".repeat(43))).thenReturn(
            new BindingReleaseReplayReceipt(command.commandId(), command.requestId(), 1, time)
        );
        authenticatedPost(
            "/api/operations/binding-releases/community/replays",
            new BindingReleaseRecoveryController.RecoveryRequest(command, "a".repeat(43))
        )
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.commandId")
            .isEqualTo(command.commandId());
        verify(recoveryAuthority).confirm(
            "10",
            ReplayCommandBinding.sha256(token),
            "community",
            command,
            "fixture-password"
        );
        verify(recoveryRpc).replay("10", ReplayCommandBinding.sha256(token), command, "a".repeat(43));
    }

    @Test
    void bindingRecoveryBadSecretBadCursorAndUnavailableNeverInventReceipt() {
        when(client.access("10")).thenReturn(
            new OperationsAccess(true, List.of(), List.of("asset:binding:read", "asset:binding:replay"))
        );
        var command = new BindingReleaseReplayCommand(
            request().command().requestId(),
            request().command().eventId(),
            0,
            request().command().reason()
        );
        authenticatedPost(
            "/api/operations/binding-releases/identity/replays",
            new BindingReleaseRecoveryController.RecoveryRequest(command, "short")
        )
            .expectStatus()
            .isBadRequest();
        authenticatedGet(
            "/api/operations/binding-releases/identity/tasks/" + command.requestId() + "/audits?beforeGeneration=0"
        )
            .expectStatus()
            .isBadRequest();
        verifyNoInteractions(recoveryRpc);
        when(recoveryRpc.replay(any(), any(), any(), any())).thenThrow(
            new OperationsUnavailableException("isolated private SQL")
        );
        authenticatedPost(
            "/api/operations/binding-releases/identity/replays",
            new BindingReleaseRecoveryController.RecoveryRequest(command, "a".repeat(43))
        )
            .expectStatus()
            .isEqualTo(503)
            .expectHeader()
            .valueEquals("Cache-Control", "no-store")
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("OPERATIONS_UNAVAILABLE");
        assertThat(
            new BindingReleaseRecoveryController.ConfirmationRequest(command, "private-password").toString()
        ).doesNotContain("private-password");
        assertThat(
            new BindingReleaseRecoveryController.RecoveryRequest(command, "a".repeat(43)).toString()
        ).doesNotContain("a".repeat(43));
    }

    private WebTestClient.ResponseSpec authenticatedPost(String uri, Object body) {
        return http
            .post()
            .uri(uri)
            .header(StpUtil.getTokenName(), token)
            .header("X-Koko-User-Id", "999")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange();
    }

    private static OperationsAccessController.ConfirmationRequest request() {
        return new OperationsAccessController.ConfirmationRequest(
            "identity",
            new OutboxReplayCommand(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                0,
                "隔离确认原因不少于十个字符"
            ),
            "fixture-password"
        );
    }
}
