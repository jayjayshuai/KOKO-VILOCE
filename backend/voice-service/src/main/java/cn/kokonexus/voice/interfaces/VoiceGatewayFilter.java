package cn.kokonexus.voice.interfaces;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 语音HTTP边界只接受可信网关，不把客户端伪造身份用于签发LiveKit令牌。 */
@Component
public class VoiceGatewayFilter extends OncePerRequestFilter {

    /** 当前网关内部共享密钥，不序列化、不写日志。 */
    private final byte[] expectedKey;

    public VoiceGatewayFilter(@Value("${koko.voice.gateway-key:}") String key) {
        if (key == null || !key.matches("[!-~]{32,256}")) {
            throw new IllegalStateException("语音内部网关密钥须为32～256位非空白ASCII字符");
        }
        expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅内部网络健康/指标采集；不能按/api/voice前缀跳过编码/别名路径或公开文档。
        String path = request.getRequestURI();
        return (
            path.equals("/actuator/health") ||
            path.equals("/actuator/health/liveness") ||
            path.equals("/actuator/health/readiness") ||
            path.equals("/actuator/prometheus")
        );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        String key = singleHeader(request, "X-Koko-Gateway-Key");
        boolean trusted =
            key != null &&
            key.length() <= 256 &&
            MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8));
        boolean discovery =
            "GET".equals(request.getMethod()) && "/api/voice/rooms/discovery".equals(request.getRequestURI());
        if (!trusted || (!discovery && !validUser(singleHeader(request, "X-Koko-User-Id")))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"语音服务仅接受可信网关请求\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 重复头/逗号合并身份不选首值，避免代理和Servlet解释不一致。 */
    private static String singleHeader(HttpServletRequest request, String name) {
        var headers = Collections.list(request.getHeaders(name));
        return headers.size() == 1 ? headers.getFirst() : null;
    }

    /** 校验与Java long领域ID一致，不接受负数、前导零、超界或空白。 */
    private static boolean validUser(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
