package fruition.core.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AgentServiceTokenFilter extends OncePerRequestFilter {

    private final byte[] serviceToken;

    public AgentServiceTokenFilter(String serviceToken) {
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return !uri.startsWith(contextPath + "/internal/agent/tools/")
                && !uri.startsWith(contextPath + "/internal/agent/skill-execution/")
                && !uri.startsWith(contextPath + "/internal/agent/folder-lookup/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader("X-Agent-Service-Token");
        if (serviceToken.length == 0 || token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), serviceToken)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Agent service token이 올바르지 않습니다.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
