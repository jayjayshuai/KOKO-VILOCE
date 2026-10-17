package cn.kokonexus.asset.interfaces;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.kokonexus.asset.application.*;
import cn.kokonexus.asset.domain.VideoDraft;
import cn.kokonexus.asset.infrastructure.storage.VideoStorage;
import cn.kokonexus.common.api.*;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实MVC/Filter及Range响应；SQL归属和FFmpeg另由隔离集成检查覆盖。 */
class VideoDraftControllerTest {

    private final String id = UUID.randomUUID().toString(),
        key = "synthetic-gateway-key-" + "x".repeat(32);
    private final VideoDraftState state = mock(VideoDraftState.class);
    private final VideoStorage storage = mock(VideoStorage.class);

    private MockMvc mvc(boolean enabled) {
        var beans = new StaticListableBeanFactory(
            enabled ? Map.of("state", state, "storage", storage, "uploads", mock(VideoDraftService.class)) : Map.of()
        );
        return MockMvcBuilders.standaloneSetup(
            new VideoDraftController(
                beans.getBeanProvider(VideoDraftService.class),
                beans.getBeanProvider(VideoDraftState.class),
                beans.getBeanProvider(VideoStorage.class)
            )
        )
            .setControllerAdvice(new GlobalExceptionHandler(), new AssetExceptionHandler())
            .addFilters(new AssetGatewayFilter(key))
            .build();
    }

    private VideoDraft ready() {
        var row = new VideoDraft();
        row.setId(id);
        row.setOwnerId(42L);
        row.setRunId(UUID.randomUUID().toString());
        row.setState("READY");
        row.setOutputBytes(10L);
        return row;
    }

    @Test
    void disabledAndUntrustedCannotOpenMediaAndErrorsAreNotCached() throws Exception {
        mvc(false)
            .perform(get("/api/assets/videos/capabilities"))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc(false)
            .perform(get("/api/assets/videos/capabilities").header("X-Koko-Gateway-Key", key))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        mvc(false)
            .perform(get("/api/assets/videos").header("X-Koko-Gateway-Key", key).header("X-Koko-User-Id", "42"))
            .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(state, storage);
    }

    @Test
    void rangesSuffixOpenEndedHeadAnd416HaveExactBytesWithoutReadingWholeObject() throws Exception {
        var row = ready();
        when(state.owned(42, id)).thenReturn(row);
        when(storage.open(VideoDraftService.outputKey(row), 2, 3)).thenReturn(
            new ByteArrayInputStream(new byte[] { 2, 3, 4 })
        );
        var mvc = mvc(true);
        mvc.perform(
            get("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
                .header("Range", "bytes=2-4")
        )
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 2-4/10"))
            .andExpect(header().string("Content-Length", "3"))
            .andExpect(content().bytes(new byte[] { 2, 3, 4 }));
        clearInvocations(storage);
        mvc.perform(
            head("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Length", "10"));
        mvc.perform(
            head("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
                .header("Range", "bytes=999-")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Length", "10"))
            .andExpect(header().doesNotExist("Content-Range"));
        for (String range : new String[] {
            "bytes=10-",
            "bytes=9-2",
            "bytes=0-1,5-6",
            "bytes=-0",
            "bytes=9999999999999999999-",
        })
            mvc.perform(
                get("/api/assets/videos/" + id + "/content")
                    .header("X-Koko-Gateway-Key", key)
                    .header("X-Koko-User-Id", "42")
                    .header("Range", range)
            )
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */10"));
        verifyNoInteractions(storage);
        assertEquals(new VideoByteRange(7, 9, true), VideoByteRange.parse("bytes=-3", 10));
        assertEquals(new VideoByteRange(0, 9, true), VideoByteRange.parse("bytes=-99", 10));
        assertEquals(new VideoByteRange(6, 9, true), VideoByteRange.parse("bytes=6-", 10));
        assertEquals(new VideoByteRange(6, 9, true), VideoByteRange.parse("bytes=6-999", 10));
    }

    @Test
    void unsupportedUnitAndUnverifiableIfRangeReturnFullRepresentation() throws Exception {
        var row = ready();
        when(state.owned(42, id)).thenReturn(row);
        when(storage.open(VideoDraftService.outputKey(row), 0, 10)).thenAnswer(call ->
            new ByteArrayInputStream(new byte[10])
        );
        var mvc = mvc(true);
        mvc.perform(
            get("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
                .header("Range", "items=0-1")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Length", "10"))
            .andExpect(header().doesNotExist("Content-Range"));
        mvc.perform(
            get("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
                .header("Range", "bytes=0-1")
                .header("If-Range", "\"unverifiable\"")
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Length", "10"))
            .andExpect(header().doesNotExist("Content-Range"));
    }

    @Test
    void ownershipAndReadyAreCheckedBeforeRangeAndNeverTouchStorageOnDeniedRead() throws Exception {
        when(state.owned(43, id)).thenThrow(new ResourceNotFoundException("视频草稿不存在"));
        var mvc = mvc(true);
        mvc.perform(
            get("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "43")
                .header("Range", "bytes=999-")
        )
            .andExpect(status().isNotFound())
            .andExpect(header().doesNotExist("Content-Range"));
        var row = ready();
        row.setState("PROCESSING");
        when(state.owned(42, id)).thenReturn(row);
        mvc.perform(
            get("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
        ).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test
    void storageFailureKeepsJsonErrorWithoutVideoLengthAndReleasesStreamingSlot() throws Exception {
        var row = ready();
        when(state.owned(42, id)).thenReturn(row);
        when(storage.open(anyString(), anyLong(), anyLong())).thenThrow(
            new ExternalDependencyUnavailableException("视频存储暂时不可用", null)
        );
        var mvc = mvc(true);
        for (int n = 0; n < 6; n++) mvc.perform(
            get("/api/assets/videos/" + id + "/content")
                .header("X-Koko-Gateway-Key", key)
                .header("X-Koko-User-Id", "42")
        )
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(header().doesNotExist("Content-Range"));
        verify(storage, times(6)).open(anyString(), eq(0L), eq(10L));
    }
}
