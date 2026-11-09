package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpAgentProposalClientTest {
    @Test void postsExactServiceAuthenticatedProposalAndMapsSuccessAndConflict() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(200, """
                    {"schema_version":"mcp.agent-proposal.v1",
                     "run_id":"00000000-0000-4000-8000-000000000003",
                     "plan_id":"00000000-0000-4000-8000-000000000004",
                     "plan_version":1,"operation_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                     "status":"awaiting_approval"}
                    """));
            server.enqueue(json(409, """
                    {"error":{"code":"IDEMPOTENCY_CONFLICT","message":"conflict","retryable":false,
                     "handle":null,"state":null,"operation_error":null}}
                    """));
            server.start();
            ObjectMapper mapper = new ObjectMapper();
            var client = new McpAgentProposalClient(new PipelineClientFactory("internal-secret"), mapper,
                    McpTokenVerifierTest.props(), server.url("/agent/runs").toString(), "agent-secret");
            McpProposalActor actor = actor("00000000-0000-4000-8000-000000000002");
            Map<String,Object> request = McpDocumentProposalService.request(actor, input("Fix title"), null, mapper);

            var response = client.create(request);

            assertThat(response.runId()).isEqualTo("00000000-0000-4000-8000-000000000003");
            var recorded = server.takeRequest();
            assertThat(recorded.getPath()).isEqualTo("/agent/runs/mcp-proposals");
            assertThat(recorded.getHeader("X-Agent-Service-Token")).isEqualTo("agent-secret");
            assertThat(recorded.getHeader("Authorization")).isNull();
            assertThat(recorded.getHeader("X-Internal-Token")).isNull();
            assertThat(recorded.getBody().readUtf8()).isEqualTo(mapper.writeValueAsString(request));
            assertThat(request.get("request_hash"))
                    .isEqualTo("sha256:9e02041dfc0c922640e56e612b235890e99d3b1074d13a67893fdfb577725c03");

            assertThatThrownBy(() -> client.create(request))
                    .isInstanceOf(McpToolException.class).hasMessage("IDEMPOTENCY_CONFLICT");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test void rejectsOversizedOrMalformedAiResponses() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(200, "x".repeat(McpTokenVerifierTest.props().maxResponseBytes() + 1)));
            server.enqueue(json(200, "{\"run_id\":\"missing-fields\"}"));
            server.start();
            var client = new McpAgentProposalClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/agent/runs").toString(), "agent-secret");
            Map<String,Object> request = Map.of("request", "bounded");

            assertThatThrownBy(() -> client.create(request))
                    .isInstanceOf(McpToolException.class).hasMessage("RESPONSE_LIMIT_EXCEEDED");
            assertThatThrownBy(() -> client.create(request))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        }
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    static McpProposalActor actor(String invocationId) {
        return new McpProposalActor(invocationId, "00000000-0000-4000-8000-000000000001",
                "user-1", "workspace-1", "https://client.example/metadata", 1,
                "MCP_CLIENT", "2025-11-25", "propose_document_edit");
    }

    static McpDocumentProposalService.Input input(String instruction) {
        return new McpDocumentProposalService.Input("request-1", "doc_1", 1,
                new McpDocumentProposalService.Target("selection", 1, 1), instruction, "x");
    }
}
