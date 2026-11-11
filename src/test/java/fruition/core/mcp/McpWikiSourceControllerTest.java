package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpWikiSourceControllerTest {
    private final McpWikiSourceService service = mock(McpWikiSourceService.class);
    private final McpWikiSourceController controller = new McpWikiSourceController(
            service, McpTokenVerifierTest.props(), new ObjectMapper());

    @Test void rejectsUnknownFieldsAndNonStartActorBeforeService() {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("""
                {"schema_version":"mcp.wiki-source.v1","actor":{
                 "invocation_id":"00000000-0000-4000-8000-000000000002",
                 "grant_id":"00000000-0000-4000-8000-000000000001","user_id":"user",
                 "workspace_id":"workspace","client_id":"https://client.example","policy_generation":2,
                 "origin":"MCP_CLIENT","spec_version":"2025-11-25","tool_name":"resume_ingest"},
                 "source_version":9}
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var response = controller.read("doc_1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((McpWikiSourceController.ErrorEnvelope) response.getBody()).error().code())
                .isEqualTo("INVALID_INPUT");
        verifyNoInteractions(service);
    }

    @Test void acceptsExactLintActorOnTheExistingSourceRoute() {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("""
                {"schema_version":"mcp.wiki-source.v1","actor":{
                 "invocation_id":"00000000-0000-4000-8000-000000000002",
                 "grant_id":"00000000-0000-4000-8000-000000000001","user_id":"user",
                 "workspace_id":"workspace","client_id":"https://client.example","policy_generation":2,
                 "origin":"MCP_CLIENT","spec_version":"2025-11-25","tool_name":"submit_lint_changes"}}
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var actor = new McpProposalActor("00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000001", "user", "workspace",
                "https://client.example", 2, "MCP_CLIENT", "2025-11-25", "submit_lint_changes");
        var expected = new McpWikiSourceService.Response("mcp.wiki-source.v1", "doc_1", "Source", "workspace", 4,
                "sha256:" + "a".repeat(64), "text/markdown", java.util.List.of());
        when(service.read(actor, "doc_1")).thenReturn(expected);

        var response = controller.read("doc_1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).read(actor, "doc_1");
    }
}
