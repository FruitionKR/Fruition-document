package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpWikiPageClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sendsExactBoundRequestAndPreservesUnicodeAndEmptyBodies() throws Exception {
        try (var server = new MockWebServer()) {
            String body = "# 한글  \n\nTrailing  \n";
            server.enqueue(json(page("page-a", "SOURCE", body)));
            server.enqueue(json(page("page-empty", "CONCEPT", "")));
            server.start();
            var client = client(server, 100_000, 10_000, 5);

            var page = client.get(identity(), "page-a");
            var empty = client.get(identity(), "page-empty");

            assertThat(page.body()).isEqualTo(body);
            assertThat(page.contentHash()).isEqualTo("sha256:" + sha256(body));
            assertThat(empty.body()).isEmpty();
            assertThat(empty.contentHash()).isEqualTo("sha256:" + sha256(""));
            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/wiki/pages/page-a");
            assertThat(request.getRequestUrl().queryParameter("workspace_id")).isEqualTo("workspace-a");
            assertThat(request.getRequestUrl().queryParameter("mcp_read")).isEqualTo("true");
            assertThat(request.getHeader("X-Internal-Token")).isEqualTo("internal");
        }
    }

    @Test void mapsWorkspaceScopedNotFoundAndRejectsPathTraversalBeforeCallingUpstream() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(error(404, "PAGE_NOT_FOUND"));
            server.enqueue(error(404, "DEPENDENCY_UNAVAILABLE"));
            server.start();
            var client = client(server, 100_000, 10_000, 5);

            assertThatThrownBy(() -> client.get(identity(), "foreign-page"))
                    .isInstanceOf(McpToolException.class).hasMessage("PAGE_NOT_FOUND");
            assertThatThrownBy(() -> client.get(identity(), "foreign-page"))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
            assertThatThrownBy(() -> client.get(identity(), "../internal"))
                    .isInstanceOf(McpToolException.class).hasMessage("INVALID_INPUT");
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test void rejectsMalformedMismatchedOrUnexpectedDependencyPayloads() throws Exception {
        try (var server = new MockWebServer()) {
            String hash = "sha256:" + sha256("body");
            server.enqueue(json(mapper.writeValueAsString(new java.util.LinkedHashMap<>(Map.of(
                    "schema_version", "mcp.wiki-page.v1", "page_id", "page-a", "page_type", "SOURCE",
                    "title", "Title", "body", "body", "content_hash", hash,
                    "data_boundary", "UNTRUSTED_WORKSPACE_CONTENT", "extra", true)))));
            server.enqueue(json(page("page-b", "SOURCE", "body")));
            server.enqueue(json(pageWith("wrong.schema", "page-a", "SOURCE", "body", hash)));
            server.enqueue(json(pageWith("mcp.wiki-page.v1", "page-a", "OTHER", "body", hash)));
            server.enqueue(json(pageWith("mcp.wiki-page.v1", "page-a", "SOURCE", "body",
                    "sha256:" + "0".repeat(64))));
            server.enqueue(json("{"));
            server.enqueue(json(pageWithValues(8, "body", hash)));
            server.enqueue(json(pageWithValues("Title", 7, "sha256:" + sha256("7"))));
            server.enqueue(json(pageWithValues("Title", "body", 8)));
            server.start();
            var client = client(server, 100_000, 10_000, 5);

            for (int i = 0; i < 9; i++) {
                assertThatThrownBy(() -> client.get(identity(), "page-a"))
                        .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
            }
        }
    }

    @Test void neutralizesPublicMarkdownAndHashesTheExactReturnedBodyAfterCheckingTheRawHash() throws Exception {
        try (var server = new MockWebServer()) {
            String rawTitle = "[Title](https://evil.example/title)";
            String rawBody = "![alt](https://evil.example/image.png)\n[Read](https://evil.example/path)";
            String sanitizedBody = "외부 이미지(evil.example)\nRead (evil.example)";
            server.enqueue(json(pageWithTitle(rawTitle, rawBody, "sha256:" + sha256(rawBody))));
            server.enqueue(json(pageWithTitle(rawTitle, rawBody, "sha256:" + sha256(sanitizedBody))));
            server.start();
            var client = client(server, 100_000, 10_000, 5);

            var page = client.get(identity(), "page-a");
            assertThat(page.title()).isEqualTo("Title (evil.example)");
            assertThat(page.body()).isEqualTo(sanitizedBody);
            assertThat(page.contentHash()).isEqualTo("sha256:" + sha256(sanitizedBody));
            assertThatThrownBy(() -> client.get(identity(), "page-a"))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        }
    }

    @Test void capsStreamedResponsesBeforeFullMaterializationAndMapsReadTimeoutSafely() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setChunkedBody("x".repeat(2_000), 16));
            server.enqueue(json(page("page-a", "SOURCE", "body")).setBodyDelay(2, TimeUnit.SECONDS));
            server.start();

            assertThatThrownBy(() -> client(server, 128, 64, 1).get(identity(), "page-a"))
                    .isInstanceOf(McpToolException.class).hasMessage("RESPONSE_LIMIT_EXCEEDED");
            assertThatThrownBy(() -> client(server, 100_000, 10_000, 1).get(identity(), "page-a"))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        }
    }

    @Test void rejectsBodiesAboveTheExistingDocumentContentLimit() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json(page("page-a", "SOURCE", "한글")));
            server.enqueue(json(page("page-a", "SOURCE", "List<String>")));
            server.start();
            assertThatThrownBy(() -> client(server, 100_000, 5, 5).get(identity(), "page-a"))
                    .isInstanceOf(McpToolException.class).hasMessage("RESPONSE_LIMIT_EXCEEDED");
            assertThatThrownBy(() -> client(server, 100_000, 12, 5).get(identity(), "page-a"))
                    .isInstanceOf(McpToolException.class).hasMessage("RESPONSE_LIMIT_EXCEEDED");
        }
    }

    private McpWikiPageClient client(MockWebServer server, int responseBytes, int bodyBytes, int timeoutSeconds) {
        var properties = new McpProperties(URI.create("https://mcp.example/mcp"),
                URI.create("https://auth.example"), List.of(URI.create("https://client.example")),
                server.url("/internal/mcp/wiki/search").uri(), "x".repeat(32).getBytes(StandardCharsets.UTF_8),
                10, 20, responseBytes, 4096, 100, 100_000, 10, bodyBytes, timeoutSeconds);
        return new McpWikiPageClient(new PipelineClientFactory("internal"), mapper, properties);
    }

    private String page(String pageId, String pageType, String body) throws Exception {
        return pageWith("mcp.wiki-page.v1", pageId, pageType, body, "sha256:" + sha256(body));
    }

    private String pageWith(String schema, String pageId, String pageType, String body, String hash) throws Exception {
        return mapper.writeValueAsString(Map.of("schema_version", schema, "page_id", pageId,
                "page_type", pageType, "title", "Title", "body", body, "content_hash", hash,
                "data_boundary", "UNTRUSTED_WORKSPACE_CONTENT"));
    }

    private String pageWithTitle(String title, String body, String hash) throws Exception {
        return mapper.writeValueAsString(Map.of("schema_version", "mcp.wiki-page.v1", "page_id", "page-a",
                "page_type", "SOURCE", "title", title, "body", body, "content_hash", hash,
                "data_boundary", "UNTRUSTED_WORKSPACE_CONTENT"));
    }

    private String pageWithValues(Object title, Object body, Object hash) throws Exception {
        return mapper.writeValueAsString(Map.of("schema_version", "mcp.wiki-page.v1", "page_id", "page-a",
                "page_type", "SOURCE", "title", title, "body", body, "content_hash", hash,
                "data_boundary", "UNTRUSTED_WORKSPACE_CONTENT"));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private MockResponse error(int status, String code) throws Exception {
        return json(mapper.writeValueAsString(Map.of("error", Map.of(
                "code", code, "message", "Safe error", "retryable", false,
                "handle", com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                "state", com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                "operation_error", com.fasterxml.jackson.databind.node.NullNode.getInstance())))).setResponseCode(status);
    }

    private static McpIdentity identity() {
        return new McpIdentity("user-a", "workspace-a", "https://client.example/client.json", "grant-a", 1,
                Set.of("read"), "MEMBER", Set.of());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
