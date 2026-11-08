package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpAccessClientTest {
    @Test void callsCurrentAuthorizeContractWithInternalTokenAndNeverForwardsBearer() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                    {"decision":"ALLOW","deny_reason":null,
                     "grant":{"grant_id":"grant","status":"ACTIVE","revocation_reason":null,"user_id":"user","workspace_id":"workspace","client_id":"https://client.example/client.json","scopes":["read"]},
                     "policy":{"enabled":true,"generation":2,"allowed_scopes":["read"]},
                     "current_access":{"role":"MEMBER","permissions":[]},"effective_scopes":["read"],"checked_at":"2026-01-01T00:00:00Z"}
                    """));
            server.start();
            var client = new McpAccessClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/").toString().replaceAll("/$", ""));
            var identity = client.authorize(new McpTokenVerifier.Token("user", "workspace",
                    "https://client.example/client.json", "grant", 2, Set.of("read")));
            assertThat(identity.role()).isEqualTo("MEMBER");
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/internal/mcp/grants/grant/authorize");
            assertThat(request.getHeader("X-Internal-Token")).isEqualTo("internal-secret");
            assertThat(request.getHeader("Authorization")).isNull();
            assertThat(request.getBody().readUtf8()).contains("\"phase\":\"TOOL_CALL\"")
                    .contains("\"execution_basis\":\"ACTIVE_GRANT\"")
                    .doesNotContain("bearer");
        }
    }

    @Test void proposalAuthorizationRequiresCurrentWriteProposeAndUsesActorIdentity() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(allow("ACTIVE"));
            server.start();
            var client = new McpAccessClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/").toString().replaceAll("/$", ""));
            var actor = actor();

            var identity = client.authorizeProposal(actor);

            assertThat(identity.scopes()).containsExactlyInAnyOrder("read", "write_propose");
            var request = server.takeRequest();
            assertThat(request.getHeader("Authorization")).isNull();
            assertThat(request.getBody().readUtf8())
                    .contains("\"phase\":\"PROPOSAL_CREATE\"")
                    .contains("\"required_scopes\":[\"write_propose\"]")
                    .contains("\"subject\":\"user\"")
                    .contains("\"workspace_id\":\"workspace\"");
        }
    }

    @Test void webApprovalAndOperationBoundaryUseTheirFrozenAuthorizationPhases() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(allow("ACTIVE"));
            server.enqueue(allow("USER_DISCONNECTED"));
            server.start();
            var client = new McpAccessClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/").toString().replaceAll("/$", ""));

            assertThat(client.authorizeWebApproval(actor()).clientName()).isEqualTo("Test MCP Client");
            assertThat(client.authorizeOperationBoundary(actor(), 2).role()).isEqualTo("MEMBER");

            assertThat(server.takeRequest().getBody().readUtf8())
                    .contains("\"phase\":\"WEB_APPROVAL\"")
                    .contains("\"required_scopes\":[\"write_propose\"]")
                    .contains("\"execution_basis\":\"ACTIVE_GRANT\"")
                    .contains("\"approved_policy_generation\":null");
            assertThat(server.takeRequest().getBody().readUtf8())
                    .contains("\"phase\":\"OPERATION_BOUNDARY\"")
                    .contains("\"required_scopes\":[]")
                    .contains("\"execution_basis\":\"EXPLICIT_WEB_APPROVAL\"")
                    .contains("\"approved_policy_generation\":2");
        }
    }

    @Test void operationBoundaryRejectsMismatchedApprovedGenerationWithoutCallingAccess() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var client = new McpAccessClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/").toString().replaceAll("/$", ""));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> client.authorizeOperationBoundary(actor(), 3))
                    .isInstanceOf(McpAuthorizationException.class).hasMessage("ACCESS_DENIED");
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test void wikiSourceRechecksCurrentWikiWriteGrantAndIngestPermission() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                    {"decision":"ALLOW","deny_reason":null,
                     "grant":{"grant_id":"00000000-0000-4000-8000-000000000001","status":"ACTIVE","revocation_reason":null,"user_id":"user","workspace_id":"workspace","client_id":"https://client.example/client.json","scopes":["read","wiki_write"]},
                     "policy":{"enabled":true,"generation":2,"allowed_scopes":["read","wiki_write"]},
                     "current_access":{"role":"MEMBER","permissions":["wiki.ingest"]},"effective_scopes":["wiki_write"],
                     "client_name":"Test MCP Client","checked_at":"2026-01-01T00:00:00Z"}
                    """));
            server.start();
            var client = new McpAccessClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/").toString().replaceAll("/$", ""));
            var sourceActor = new McpProposalActor(actor().invocationId(), actor().grantId(), actor().userId(),
                    actor().workspaceId(), actor().clientId(), actor().policyGeneration(), actor().origin(),
                    actor().specVersion(), "start_ingest");

            assertThat(client.authorizeWikiSource(sourceActor).permissions()).contains("wiki.ingest");
            assertThat(server.takeRequest().getBody().readUtf8())
                    .contains("\"phase\":\"PROPOSAL_CREATE\"")
                    .contains("\"required_scopes\":[\"wiki_write\"]")
                    .contains("\"required_permissions\":[\"wiki.ingest\"]")
                    .contains("\"execution_basis\":\"ACTIVE_GRANT\"")
                    .contains("\"approved_policy_generation\":null");
        }
    }

    @Test void lintSourceRequiresOnlyWikiLintAndNeverRelabelsItAsIngest() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                    {"decision":"ALLOW","deny_reason":null,
                     "grant":{"grant_id":"00000000-0000-4000-8000-000000000001","status":"ACTIVE","revocation_reason":null,"user_id":"user","workspace_id":"workspace","client_id":"https://client.example/client.json","scopes":["read","wiki_write"]},
                     "policy":{"enabled":true,"generation":2,"allowed_scopes":["read","wiki_write"]},
                     "current_access":{"role":"MEMBER","permissions":["wiki.lint"]},"effective_scopes":["wiki_write"],
                     "client_name":"Test MCP Client","checked_at":"2026-01-01T00:00:00Z"}
                    """));
            server.start();
            var client = new McpAccessClient(new PipelineClientFactory("internal-secret"), new ObjectMapper(),
                    McpTokenVerifierTest.props(), server.url("/").toString().replaceAll("/$", ""));
            var lintActor = new McpProposalActor(actor().invocationId(), actor().grantId(), actor().userId(),
                    actor().workspaceId(), actor().clientId(), actor().policyGeneration(), actor().origin(),
                    actor().specVersion(), "submit_lint_changes");

            assertThat(client.authorizeWikiSource(lintActor).permissions()).containsExactly("wiki.lint");
            assertThat(server.takeRequest().getBody().readUtf8())
                    .contains("\"phase\":\"PROPOSAL_CREATE\"")
                    .contains("\"required_permissions\":[\"wiki.lint\"]")
                    .doesNotContain("wiki.ingest");
        }
    }

    private static McpProposalActor actor() {
        return new McpProposalActor("00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000001", "user", "workspace",
                "https://client.example/client.json", 2, "MCP_CLIENT", "2025-11-25",
                "propose_document_edit");
    }

    private static MockResponse allow(String status) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"decision":"ALLOW","deny_reason":null,
                 "grant":{"grant_id":"00000000-0000-4000-8000-000000000001","status":"%s","revocation_reason":null,"user_id":"user","workspace_id":"workspace","client_id":"https://client.example/client.json","scopes":["read","write_propose"]},
                 "policy":{"enabled":true,"generation":2,"allowed_scopes":["read","write_propose"]},
                 "current_access":{"role":"MEMBER","permissions":[]},"effective_scopes":["read","write_propose"],
                 "client_name":"Test MCP Client","checked_at":"2026-01-01T00:00:00Z"}
                """.formatted(status));
    }
}
