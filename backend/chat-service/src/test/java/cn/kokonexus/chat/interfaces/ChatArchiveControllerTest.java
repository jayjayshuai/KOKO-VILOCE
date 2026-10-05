package cn.kokonexus.chat.interfaces;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.kokonexus.chat.application.ChatArchiveService;
import cn.kokonexus.common.api.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatArchiveControllerTest {

    @Test
    void emptyWindowPreservesCursorAndBoundedDto() throws Exception {
        var service = mock(ChatArchiveService.class);
        when(service.search(42, "c", "word", null, 20)).thenReturn(
            new ChatArchiveService.MessageSlice(List.of(), 206L)
        );
        var mvc = MockMvcBuilders.standaloneSetup(new ChatArchiveController(service)).build();
        mvc.perform(get("/api/chat/conversations/c/search?query=word").header("X-Koko-User-Id", "42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.nextBefore").value(206));
    }

    @Test
    void deleteUsesAuthenticatedOwnerAndNoContentContract() throws Exception {
        var service = mock(ChatArchiveService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChatArchiveController(service)).build();
        mvc.perform(delete("/api/chat/conversations/c/bookmarks/m").header("X-Koko-User-Id", "42"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        verify(service).remove(42, "c", "m");
    }

    @Test
    void timeoutReturnsStableUnavailableWithoutSqlLeak() throws Exception {
        var service = mock(ChatArchiveService.class);
        when(service.search(anyLong(), anyString(), anyString(), any(), anyInt())).thenThrow(
            new QueryTimeoutException("sensitive SQL")
        );
        var mvc = MockMvcBuilders.standaloneSetup(new ChatArchiveController(service))
            .setControllerAdvice(new ChatAuthenticationAdvice(), new GlobalExceptionHandler())
            .build();
        mvc.perform(get("/api/chat/conversations/c/search?query=word").header("X-Koko-User-Id", "42"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HISTORY_BUSY"))
            .andExpect(
                content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive SQL")))
            );
    }
}
