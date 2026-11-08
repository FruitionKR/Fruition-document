package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.security.AgentServiceTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpPlanContextControllerTest {
    private final McpPlanContextService service = mock(McpPlanContextService.class);
    private final McpPlanContextController controller = new McpPlanContextController(
            service, McpTokenVerifierTest.props(), new ObjectMapper());

    @Test void acceptsOnlyTheExactPlanContextRequest() {
        when(service.read(any(), isNull(), anyList(), anyList())).thenReturn(new McpPlanContextService.Response(
                "mcp.plan-context.v1", "workspace", null, List.of(), List.of()));
        String valid = requestBody();

        assertThat(controller.read(request(valid)).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.read(request(valid.replace("\"resources\":[]", "\"resources\":[],\"unknown\":1")))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.read(request(valid.replace("\"policy_generation\":2", "\"policy_generation\":2.0")))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.read(request(valid.replace("\"tool_name\":\"propose_plan\"",
                "\"tool_name\":\"propose_document_edit\""))).getStatusCode().value()).isEqualTo(400);
        verify(service, times(1)).read(any(), isNull(), anyList(), anyList());
    }

    @Test void serviceTokenProtectsTheEndpoint() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AgentServiceTokenFilter("agent-token")).build();
        mvc.perform(post("/internal/agent/mcp/plan-context").content(requestBody()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/agent/mcp/plan-context")
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

    private static String requestBody() {
        return """
                {"schema_version":"mcp.plan-context.v1","actor":{
                  "invocation_id":"00000000-0000-4000-8000-000000000002",
                  "grant_id":"00000000-0000-4000-8000-000000000001",
                  "user_id":"user","workspace_id":"workspace",
                  "client_id":"https://client.example/client.json","policy_generation":2,
                  "origin":"MCP_CLIENT","spec_version":"2025-11-25","tool_name":"propose_plan"},
                  "target_folder_id":null,"resources":[],"edit_documents":[]}
                """;
    }
}
