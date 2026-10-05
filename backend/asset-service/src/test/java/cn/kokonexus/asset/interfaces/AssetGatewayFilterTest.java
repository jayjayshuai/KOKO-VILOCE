package cn.kokonexus.asset.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AssetGatewayFilterTest {

    private static final String KEY = "k".repeat(48);

    @Test
    void refusesMissingOrForgedGatewayKey() throws Exception {
        AssetGatewayFilter filter = new AssetGatewayFilter(KEY);
        var request = new MockHttpServletRequest("POST", "/api/assets/images");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());

        var forged = new MockHttpServletRequest("POST", "/api/assets/images");
        forged.addHeader("X-Koko-Gateway-Key", "wrong");
        var forgedResponse = new MockHttpServletResponse();
        filter.doFilter(forged, forgedResponse, new MockFilterChain());
        assertEquals(403, forgedResponse.getStatus());
    }

    @Test
    void passesOnlyCorrectGatewayKey() throws Exception {
        AssetGatewayFilter filter = new AssetGatewayFilter(KEY);
        var request = new MockHttpServletRequest("GET", "/api/assets/images/abc");
        request.addHeader("X-Koko-Gateway-Key", KEY);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    @Test
    void refusesWeakConfiguration() {
        assertThrows(IllegalStateException.class, () -> new AssetGatewayFilter("short"));
    }
}
