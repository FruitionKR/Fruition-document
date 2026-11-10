package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.Document;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.core.usage.service.CreditService;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpWikiSearchClientTest {
    @Test void sendsSchemaCompatibleJsonWithContentLength() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("""
                    {"schema_version":"mcp.wiki-search.v1","results":[],"next_cursor":null,
                     "truncated":false,"data_boundary":"UNTRUSTED_WORKSPACE_CONTENT"}
                    """));
            server.start();
            var client = new McpWikiSearchClient(new PipelineClientFactory("internal"), new ObjectMapper(),
                    props(server, 64), mock(McpDocumentDataGateway.class), credits(true));

            client.search(new McpIdentity("user", "workspace", "https://client.example/client.json",
                    "00000000-0000-4000-8000-000000000001", 3, Set.of("read"), "OWNER", Set.of()),
                    "Aurora release", null, 20);

            var request = server.takeRequest();
            assertThat(request.getHeader("Content-Length")).isEqualTo(Long.toString(request.getBodySize()));
            assertThat(request.getHeader("Transfer-Encoding")).isNull();
            var root = new ObjectMapper().readTree(request.getBody().readUtf8());
            assertThat(root.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("schema_version", "actor", "query", "cursor", "page_size", "jev_allowed");
            assertThat(root.path("jev_allowed").isBoolean()).isTrue();
            assertThat(root.path("jev_allowed").booleanValue()).isTrue();
            assertThat(root.path("schema_version").asText()).isEqualTo("mcp.wiki-search.v1");
            assertThat(root.path("query").asText()).isEqualTo("Aurora release");
            assertThat(root.path("cursor").isNull()).isTrue();
            assertThat(root.path("page_size").isIntegralNumber()).isTrue();
            assertThat(root.path("page_size").asInt()).isEqualTo(20);
            var actor = root.path("actor");
            assertThat(actor.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "invocation_id", "grant_id", "user_id", "workspace_id", "client_id",
                    "policy_generation", "origin", "spec_version", "tool_name");
            assertThat(UUID.fromString(actor.path("invocation_id").asText()).version()).isEqualTo(4);
            assertThat(actor.path("grant_id").asText()).isEqualTo("00000000-0000-4000-8000-000000000001");
            assertThat(actor.path("client_id").asText()).isEqualTo("https://client.example/client.json");
            assertThat(actor.path("policy_generation").isIntegralNumber()).isTrue();
            assertThat(actor.path("policy_generation").asLong()).isEqualTo(3);
            assertThat(actor.path("origin").asText()).isEqualTo("MCP_CLIENT");
            assertThat(actor.path("spec_version").asText()).isEqualTo("2025-11-25");
            assertThat(actor.path("tool_name").asText()).isEqualTo("search_wiki");
        }
    }

    @Test void historicalEvidenceNeedsCurrentReadAccessButNotCurrentIngestEquality() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(response(null, """
                    [external](https://evil.example/path) ![pixel](https://evil.example/p.png)

                    <b>raw</b> [relative](/docs) [anchor](#part) `https://code.example`
                    """)));
            server.start();
            var documents = mock(McpDocumentDataGateway.class);
            var viewer = new DocumentAccessPolicy.Viewer("user", true, false, Map.of(), Map.of(), Map.of());
            Document advanced = new Document("doc_a", "workspace", "other", "a.md", "text/markdown", 1,
                    null, "b".repeat(64));
            when(documents.viewer("workspace", "user", true, false)).thenReturn(viewer);
            when(documents.find("workspace", "doc_a")).thenReturn(Optional.of(advanced));
            var client = new McpWikiSearchClient(new PipelineClientFactory("internal"), new ObjectMapper(),
                    props(server, 64), documents, credits(true));

            var result = client.search(identity(), "query", null, 1).results().getFirst();

            assertThat(result.snippet()).doesNotContain("https://evil.example", "<b>")
                    .contains("external (evil.example)", "외부 이미지(evil.example)", "raw",
                            "[relative](/docs)", "[anchor](#part)", "`https://code.example`");
            assertThat(result.evidence()).containsExactly(new McpWikiSearchClient.Evidence("doc_a", "B0001"));
            assertThat(server.takeRequest().getHeader("X-Internal-Token")).isEqualTo("internal");
        }
    }

    @Test void validatesReturnedCursorAtExactUtf8CapBeforePublishingIt() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(response("가".repeat(21) + "a", "safe")));
            server.enqueue(json(response("가".repeat(21) + "ab", "safe")));
            server.start();
            var documents = mock(McpDocumentDataGateway.class);
            var viewer = new DocumentAccessPolicy.Viewer("user", true, false, Map.of(), Map.of(), Map.of());
            when(documents.viewer("workspace", "user", true, false)).thenReturn(viewer);
            when(documents.find("workspace", "doc_a")).thenReturn(Optional.of(new Document(
                    "doc_a", "workspace", "other", "a.md", "text/markdown", 1, null, "a".repeat(64))));
            var client = new McpWikiSearchClient(new PipelineClientFactory("internal"), new ObjectMapper(),
                    props(server, 64), documents, credits(true));

            assertThat(client.search(identity(), "query", null, 1).nextCursor()
                    .getBytes(StandardCharsets.UTF_8)).hasSize(64);
            assertThatThrownBy(() -> client.search(identity(), "query", null, 1))
                    .isInstanceOf(McpToolException.class).hasMessage("RESPONSE_LIMIT_EXCEEDED");
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String response(String cursor, String snippet) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "schema_version", "mcp.wiki-search.v1",
                "results", List.of(Map.of("page_id", "page_a", "page_type", "SOURCE", "title", "Title",
                        "version", 1, "content_hash", "sha256:" + "c".repeat(64), "snippet", snippet,
                        "evidence", List.of(Map.of("document_id", "doc_a", "block_id", "B0001")))),
                "next_cursor", cursor == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : cursor,
                "truncated", cursor != null, "data_boundary", "UNTRUSTED_WORKSPACE_CONTENT"));
    }

    @Test void sendsJevAllowedFalseWhenUserHasNoUsableCredit() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("""
                    {"schema_version":"mcp.wiki-search.v1","results":[],"next_cursor":null,
                     "truncated":false,"data_boundary":"UNTRUSTED_WORKSPACE_CONTENT"}
                    """));
            server.start();
            var client = new McpWikiSearchClient(new PipelineClientFactory("internal"), new ObjectMapper(),
                    props(server, 64), mock(McpDocumentDataGateway.class), credits(false));

            client.search(identity(), "Aurora release", null, 20);

            var root = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertThat(root.path("jev_allowed").isBoolean()).isTrue();
            assertThat(root.path("jev_allowed").booleanValue()).isFalse();
        }
    }

    private static CreditService credits(boolean allowed) {
        CreditService credits = mock(CreditService.class);
        when(credits.paidAssistAllowed("user")).thenReturn(allowed);
        return credits;
    }

    private static McpIdentity identity() {
        return new McpIdentity("user", "workspace", "https://client.example/client.json", "grant", 1,
                Set.of("read"), "OWNER", Set.of());
    }

    private static McpProperties props(MockWebServer server, int cursorBytes) {
        return new McpProperties(URI.create("https://mcp.example/mcp"), URI.create("https://auth.example"),
                List.of(URI.create("https://client.example")), server.url("/search").uri(),
                "x".repeat(32).getBytes(StandardCharsets.UTF_8), 20, 20, 100_000, cursorBytes,
                100, 100_000, 10, 10_000, 5);
    }
}
