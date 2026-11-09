package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.security.AgentServiceTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpDocumentEditContextControllerTest {
    private final McpDocumentEditContextService service = mock(McpDocumentEditContextService.class);
    private final McpDocumentEditContextController controller = new McpDocumentEditContextController(
            service, McpTokenVerifierTest.props(), new ObjectMapper());

    @Test void acceptsTheExactServiceDtoAndRejectsUnknownDuplicateAndCoercedFields() {
        when(service.read(any(), eq("doc_1"), eq(3L))).thenReturn(new McpDocumentEditContextService.Response(
                "mcp.document-edit-context.v1", "doc_1", "workspace", 3,
                "sha256:" + "a".repeat(64), "body", "Document", null));
        String valid = requestBody("\"base_version\":3");

        assertThat(controller.read(request(valid)).getStatusCode().value()).isEqualTo(200);
        String compact = valid.trim();
        assertThat(controller.read(request(compact.substring(0, compact.length() - 1) + ",\"unknown\":1}"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.read(request(compact.substring(0, compact.length() - 1) + ",\"actor\":{}}"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.read(request(requestBody("\"base_version\":3.0"))).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.read(request(requestBody("\"base_version\":true"))).getStatusCode().value())
                .isEqualTo(400);
        verify(service, times(1)).read(any(), eq("doc_1"), eq(3L));
    }

    @Test void serviceTokenIsRequiredBeforeTheBodyOrServiceIsReached() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AgentServiceTokenFilter("agent-token"))
                .build();

        mvc.perform(post("/internal/agent/mcp/document-edit-context").content("{"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/agent/mcp/document-edit-context")
                        .header("X-Agent-Service-Token", "agent-token").content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static String requestBody(String versionField) {
        return """
                {"schema_version":"mcp.document-edit-context.v1","actor":{
                  "invocation_id":"00000000-0000-4000-8000-000000000002",
                  "grant_id":"00000000-0000-4000-8000-000000000001",
                  "user_id":"user","workspace_id":"workspace",
                  "client_id":"https://client.example/client.json","policy_generation":2,
                  "origin":"MCP_CLIENT","spec_version":"2025-11-25","tool_name":"propose_document_edit"},
                  "document_id":"doc_1",%s}
                """.formatted(versionField);
    }
}
