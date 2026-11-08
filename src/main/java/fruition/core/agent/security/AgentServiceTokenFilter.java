package fruition.core.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

public class AgentServiceTokenFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/internal/agent/tools";
    private static final Pattern MCP_WIKI_SOURCE =
            Pattern.compile("/internal/documents/[^/]+/mcp-wiki-source");

    private final byte[] serviceToken;

    public AgentServiceTokenFilter(String serviceToken) {
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = InternalRequestPaths.canonicalPath(request);
        return !MCP_WIKI_SOURCE.matcher(path).matches() && java.util.stream.Stream.of(
                        PROTECTED_PREFIX, "/internal/agent/skill-execution", "/internal/agent/folder-lookup",
                        "/internal/agent/runs", "/internal/agent/mcp", "/internal/mcp/wiki")
                .noneMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
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
