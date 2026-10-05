package cn.kokonexus.asset.interfaces;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.kokonexus.asset.application.AssetApplicationService;
import cn.kokonexus.asset.application.AssetRegistrationService;
import cn.kokonexus.asset.domain.AssetReferenceSnapshot;
import cn.kokonexus.asset.infrastructure.rpc.AssetReferenceUnavailableException;
import cn.kokonexus.common.api.GlobalExceptionHandler;
import cn.kokonexus.common.api.ResourceNotFoundException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 MVC 参数/异常映射；RPC 和存储网络仍由单独集成验收覆盖。 */
class AssetReferenceControllerTest {

    /** 固定合成资产，不包含真实用户资源。 */
    private static final String ID = "0fc78935-9839-4721-92f5-7aaf13f00877";
    /** 测试专用内部网关密钥，绝不能作为部署默认值。 */
    private static final String KEY = "isolated-mvc-gateway-key-20261004-test";
    /** 可精确控制用例失败，验证 HTTP 契约而非存储行为。 */
    private final AssetApplicationService service = mock(AssetApplicationService.class);
    /** 真实 MVC 及网关来源过滤器，不启动公网监听。 */
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
        new AssetController(service, mock(AssetRegistrationService.class))
    )
        .setControllerAdvice(new AssetExceptionHandler(), new GlobalExceptionHandler())
        .addFilters(new AssetGatewayFilter(KEY))
        .build();

    @Test
    void successIsNoStoreAndContainsOnlySnapshotFields() throws Exception {
        when(service.references(42, ID)).thenReturn(
            new AssetReferenceSnapshot(true, false, OffsetDateTime.parse("2026-10-04T07:00:00Z"))
        );
        mvc.perform(
            get("/api/assets/images/{id}/references", ID)
                .header("X-Koko-User-Id", "42")
                .header("X-Koko-Gateway-Key", KEY)
        )
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.profileReferenced").value(true))
            .andExpect(jsonPath("$.postReferenced").value(false))
            .andExpect(jsonPath("$.checkedAt").exists())
            .andExpect(jsonPath("$.objectKey").doesNotExist())
            .andExpect(jsonPath("$.deleteAllowed").doesNotExist());
    }

    @Test
    void unavailableDomainIs503NotUnreferencedAndDoesNotExposeCause() throws Exception {
        when(service.references(42, ID)).thenThrow(
            new AssetReferenceUnavailableException(new IllegalStateException("secret-private-database"))
        );
        mvc.perform(
            get("/api/assets/images/{id}/references", ID)
                .header("X-Koko-User-Id", "42")
                .header("X-Koko-Gateway-Key", KEY)
        )
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("ASSET_REFERENCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("媒体引用状态暂时无法核验"))
            .andExpect(jsonPath("$.profileReferenced").doesNotExist());
    }

    @Test
    void foreignAssetIs404AndNotCached() throws Exception {
        when(service.references(43, ID)).thenThrow(new ResourceNotFoundException("媒体资产不存在"));
        mvc.perform(
            get("/api/assets/images/{id}/references", ID)
                .header("X-Koko-User-Id", "43")
                .header("X-Koko-Gateway-Key", KEY)
        )
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void untrustedDirectRequestIsRejectedBeforeService() throws Exception {
        mvc.perform(get("/api/assets/images/{id}/references", ID).header("X-Koko-User-Id", "42")).andExpect(
            status().isForbidden()
        );
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void missingIdentityCannotReachReferenceLookup() throws Exception {
        mvc.perform(get("/api/assets/images/{id}/references", ID).header("X-Koko-Gateway-Key", KEY)).andExpect(
            status().isBadRequest()
        );
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
