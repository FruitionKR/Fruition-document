package fruition.core.agent.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTokenFilterTest {
    private final AgentServiceTokenFilter filter = new AgentServiceTokenFilter("secret");

    @Test void protectsOnlyExactMcpWikiSourceCallbackShape() throws ServletException, IOException {
        assertStatus("/internal/documents/doc_1/mcp-wiki-source", null, 401);
        assertStatus("/internal/documents/doc_1/mcp-wiki-source", "secret", 200);
        assertStatus("/internal/documents/doc_1/other", null, 200);
        assertStatus("/internal/documents/doc_1/mcp-wiki-source/extra", null, 200);
    }

    private void assertStatus(String path, String token, int expected) throws ServletException, IOException {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (token != null) request.addHeader("X-Agent-Service-Token", token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(expected);
    }
}
