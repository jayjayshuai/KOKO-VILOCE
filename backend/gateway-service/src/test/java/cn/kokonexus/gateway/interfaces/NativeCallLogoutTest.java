package cn.kokonexus.gateway.interfaces;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.api.voice.MediaAdmissionUnavailableException;
import cn.kokonexus.gateway.infrastructure.MediaAdmissionClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

/** 注销的网站桩明确隔离；一个媒体域不可用不能阻止另一个域登记清退。 */
class NativeCallLogoutTest {

    @Test
    void publicRetirementFailureStillAttemptsNativeRetirementAfterWebsiteLogout() {
        var media = mock(MediaAdmissionClient.class);
        when(media.retireWebsiteSession(any())).thenThrow(new MediaAdmissionUnavailableException());
        when(media.retireNativeWebsiteSession(any())).thenReturn(true);
        var controller = new AuthController();
        ReflectionTestUtils.setField(controller, "mediaAdmission", media);
        ReflectionTestUtils.setField(controller, "mediaEnabled", true);
        ReflectionTestUtils.setField(controller, "nativeCallsEnabled", true);
        var immediate = reactor.core.scheduler.Schedulers.immediate();
        var strategy = cn.dev33.satoken.strategy.SaStrategy.instance;
        var previousRequest = strategy.createSaRequest;
        var previousResponse = strategy.createSaResponse;
        var previousStorage = strategy.createSaStorage;
        new cn.dev33.satoken.reactor.spring.SaTokenContextRegister();
        // 静态桩在工作线程不传播，采用Sa上下文之外的独立即时Scheduler只用于本测试。
        try (var hooks = mockStatic(reactor.core.scheduler.Schedulers.class); var token = mockStatic(StpUtil.class)) {
            hooks.when(reactor.core.scheduler.Schedulers::boundedElastic).thenReturn(immediate);
            token.when(StpUtil::getLoginIdAsString).thenReturn("42");
            token.when(StpUtil::getTokenValue).thenReturn("synthetic-cookie");
            assertThrows(MediaAdmissionUnavailableException.class, () ->
                controller.logout(MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout"))).block()
            );
            token.verify(StpUtil::logout);
            verify(media).retireWebsiteSession(any());
            verify(media).retireNativeWebsiteSession(any());
        } finally {
            strategy.createSaRequest = previousRequest;
            strategy.createSaResponse = previousResponse;
            strategy.createSaStorage = previousStorage;
        }
    }
}
