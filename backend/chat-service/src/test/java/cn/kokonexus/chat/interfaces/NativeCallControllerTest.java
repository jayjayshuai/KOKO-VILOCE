package cn.kokonexus.chat.interfaces;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.kokonexus.chat.application.*;
import cn.kokonexus.chat.transport.ChatSecurity;
import cn.kokonexus.common.api.GlobalExceptionHandler;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** MVC校验及真实下游过滤器；可信网关会话与SQL事实分别由对应集成检查覆盖。 */
class NativeCallControllerTest {

    @Test
    void disabledCapabilityIsFalseAndMutationReturnsUnavailableWithoutBackendOrMedia() throws Exception {
        var beans = new StaticListableBeanFactory();
        var controller = new NativeCallController(
            beans.getBeanProvider(NativeCallService.class),
            beans.getBeanProvider(NativeCallMediaService.class)
        );
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/chat/calls/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/chat/calls/current").header("X-Koko-User-Id", "1")).andExpect(
            status().isServiceUnavailable()
        );
    }

    @Test
    void snowflakeTargetsStayStringsAndInvalidOrMissingCommandCannotReachService() throws Exception {
        var calls = mock(NativeCallService.class);
        var media = mock(NativeCallMediaService.class);
        var beans = new StaticListableBeanFactory(Map.of("calls", calls, "media", media));
        var mvc = MockMvcBuilders.standaloneSetup(
            new NativeCallController(
                beans.getBeanProvider(NativeCallService.class),
                beans.getBeanProvider(NativeCallMediaService.class)
            )
        )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        String call = UUID.randomUUID().toString(),
            request = UUID.randomUUID().toString(),
            scope = "a".repeat(64);
        mvc.perform(
            post("/api/chat/calls")
                .header("X-Koko-User-Id", "1")
                .header("X-Koko-Website-Scope", scope)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"conversationId\":\"" +
                        call +
                        "\",\"requestId\":\"" +
                        request +
                        "\",\"targets\":[\"2109124317675118594\"]}"
                )
        ).andExpect(status().isOk());
        verify(calls).invite(1, scope, call, request, java.util.List.of(2109124317675118594L));
        clearInvocations(calls);
        mvc.perform(
            post("/api/chat/calls/" + call + "/commands")
                .header("X-Koko-User-Id", "1")
                .header("X-Koko-Website-Scope", scope)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + request + "\",\"type\":\"ACCEPT\"}")
        ).andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat/calls/current").header("X-Koko-User-Id", "1")).andExpect(status().isNoContent());
        verify(calls).current(1);
        verifyNoMoreInteractions(calls);
        verifyNoInteractions(media);
    }

    @Test
    void downstreamRejectsForgedIdentityAndAllPrivateCallResponsesAreNoStore() throws Exception {
        var beans = new StaticListableBeanFactory();
        var controller = new NativeCallController(
            beans.getBeanProvider(NativeCallService.class),
            beans.getBeanProvider(NativeCallMediaService.class)
        );
        var security = mock(ChatSecurity.class);
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(new ChatGatewayFilter(security)).build();
        mvc.perform(get("/api/chat/calls/capabilities").header("X-Koko-User-Id", "1"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"));
        when(security.trusted("synthetic-key")).thenReturn(true);
        mvc.perform(
            get("/api/chat/calls/capabilities")
                .header("X-Koko-User-Id", "1")
                .header("X-Koko-Gateway-Key", "synthetic-key")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }
}
