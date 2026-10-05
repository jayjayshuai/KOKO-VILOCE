package cn.kokonexus.chat.interfaces;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import cn.kokonexus.chat.application.ChatDirectory;
import cn.kokonexus.chat.application.ChatSafetyService;
import cn.kokonexus.chat.domain.ChatReport;
import cn.kokonexus.common.api.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** MVC 契约/身份检查测试；Sa-Token 在公开集成测试另验，数据库在隔离 MySQL 另验。 */
class ChatSafetyControllerTest {

    private final ChatSafetyService service = mock(ChatSafetyService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatSafetyController(service, mock(ChatDirectory.class)))
            .setControllerAdvice(new GlobalExceptionHandler(), new ChatAuthenticationAdvice())
            .build();
    }

    @Test
    void deniedPermissionNeverReadsEvidence() throws Exception {
        try (var token = mockStatic(StpUtil.class)) {
            token.when(StpUtil::getLoginIdAsLong).thenReturn(42L);
            token
                .when(() -> StpUtil.checkPermission("chat:reports:read"))
                .thenThrow(new NotPermissionException("chat:reports:read"));
            mvc.perform(get("/api/chat/moderation/reports").header("X-Koko-User-Id", "42"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(service);
        }
    }

    @Test
    void mismatchedTrustedIdentityNeverReadsEvidence() throws Exception {
        try (var token = mockStatic(StpUtil.class)) {
            token.when(StpUtil::getLoginIdAsLong).thenReturn(43L);
            mvc.perform(get("/api/chat/moderation/reports").header("X-Koko-User-Id", "42")).andExpect(
                status().isForbidden()
            );
            verifyNoInteractions(service);
        }
    }

    @Test
    void missingReviewVersionIsRejectedBeforeTransaction() throws Exception {
        mvc.perform(
            patch("/api/chat/moderation/reports/36f1fb46-91cd-4f32-b174-55b0c5d4bf17")
                .header("X-Koko-User-Id", "42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"RESOLVED\",\"note\":\"checked\"}")
        ).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void ordinaryReportProjectionNeverContainsEvidenceOrOtherIdentities() throws Exception {
        var report = new ChatReport();
        report.setId("36f1fb46-91cd-4f32-b174-55b0c5d4bf17");
        report.setMessageId("m");
        report.setConversationId("c");
        report.setReporterId(42L);
        report.setReportedUserId(43L);
        report.setReason("SPAM");
        report.setDetail("check");
        report.setEvidenceBody("sensitive evidence");
        report.setStatus("PENDING");
        report.setVersion(0L);
        report.setCreatedAt(LocalDateTime.now());
        when(service.mine(42, report.getId())).thenReturn(report);
        mvc.perform(get("/api/chat/reports/" + report.getId()).header("X-Koko-User-Id", "42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reportedUserId").value("43"))
            .andExpect(jsonPath("$.evidenceBody").doesNotExist())
            .andExpect(jsonPath("$.reporterId").doesNotExist());
    }
}
