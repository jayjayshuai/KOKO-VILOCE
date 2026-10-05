package cn.kokonexus.asset.interfaces;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** asset-service：AssetGatewayFilter 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class AssetGatewayFilter extends OncePerRequestFilter {

    /** 可信网关内部密钥字节，常量时间比较。 */
    private final byte[] expectedKey;

    public AssetGatewayFilter(@Value("${koko.asset.gateway-key}") String gatewayKey) {
        if (gatewayKey == null || gatewayKey.length() < 32) {
            throw new IllegalStateException("媒体资产内部网关密钥未配置或过短");
        }
        this.expectedKey = gatewayKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/assets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String supplied = request.getHeader("X-Koko-Gateway-Key");
        if (supplied == null || !MessageDigest.isEqual(expectedKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"媒体服务仅接受网关请求\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
