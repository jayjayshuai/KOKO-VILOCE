package cn.kokonexus.chat.interfaces;

import cn.kokonexus.chat.transport.ChatSecurity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 聊天 API 和文档仅允许已鉴权网关访问，不公开下游端口。 */
@Component
@RequiredArgsConstructor
public class ChatGatewayFilter extends OncePerRequestFilter {

    /** 内部密钥与 Origin 校验。 */
    private final ChatSecurity security;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String userId = request.getHeader("X-Koko-User-Id");
        String origin = request.getHeader("Origin");
        if (
            !security.trusted(request.getHeader("X-Koko-Gateway-Key")) ||
            userId == null ||
            !userId.matches("[1-9][0-9]{0,18}") ||
            (origin != null && !security.allowedOrigin(origin))
        ) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"仅接受已认证网关的同源请求\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
