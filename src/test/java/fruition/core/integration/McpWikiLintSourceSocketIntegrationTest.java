package fruition.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.document.dto.MarkdownDocumentCreateRequest;
import fruition.core.document.service.DocumentService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "test.isolated-database=i9_lint_source_socket",
        "app.mcp.enabled=true",
        "app.mcp.resource-uri=https://mcp.local.test/mcp",
        "app.mcp.authorization-server-uri=https://auth.local.test",
        "app.mcp.allowed-origins=https://client.example",
        "app.mcp.review-origin=https://review.local.test",
        "app.mcp.wiki-search-uri=https://ai.local.test/internal/mcp/wiki/search",
        "app.mcp.cursor-secret=01234567890123456789012345678901",
        "app.mcp.max-page-size=10",
        "app.mcp.max-candidates=20",
        "app.mcp.max-response-bytes=1000000",
        "app.mcp.max-cursor-bytes=4096",
        "app.mcp.max-source-blocks=100",
        "app.mcp.max-source-bytes=1000000",
        "app.mcp.max-document-blocks-per-page=20",
        "app.mcp.max-document-content-bytes=100000",
        "app.mcp.dependency-timeout-seconds=3",
        "app.skill.agent-token=i9-agent-token",
        "AGENT_INTERNAL_TOKEN=i9-agent-token",
        "app.internal.callback-token=i9-callback-token",
        "app.jwt.issuer=i9-local-issuer",
        "app.jwt.key-id=i9-local-key",
        "app.jwt.secret=",
        "app.scheduling.enabled=false"
})
@Import(TestcontainersConfiguration.class)
class McpWikiLintSourceSocketIntegrationTest {
    private static final String USER = "user_i9_lint_socket";
    private static final String WORKSPACE = "workspace_i9_lint_socket";
    private static final String CLIENT = "https://client.example/i9-lint.json";
    private static final String GRANT = "00000000-0000-4000-8000-000000000031";
    private static final MockWebServer ACCESS;
    /** pipeline source_blocks 레지스트리 대역. 원문 두 블록에 레지스트리 ID를 준다. */
    private static final MockWebServer AI;
    private static final String PUBLIC_KEY;

    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(
                    generator.generateKeyPair().getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----";
            ACCESS = new MockWebServer();
            ACCESS.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                    {"decision":"ALLOW","deny_reason":null,
                     "grant":{"grant_id":"%s","status":"ACTIVE","revocation_reason":null,"user_id":"%s",
                       "workspace_id":"%s","client_id":"%s","scopes":["wiki_write"]},
                     "policy":{"enabled":true,"generation":2,"allowed_scopes":["wiki_write"]},
                     "current_access":{"role":"OWNER","permissions":["wiki.lint"]},
                     "effective_scopes":["wiki_write"],"client_name":"I9 Socket Client",
                     "checked_at":"2026-11-08T00:00:00Z"}
                    """.formatted(GRANT, USER, WORKSPACE, CLIENT)));
            ACCESS.start();
            AI = new MockWebServer();
            AI.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    if (!"/internal/mcp/source-blocks/resolve".equals(request.getPath())
                            || !"i9-agent-token".equals(request.getHeader("X-Agent-Service-Token"))) {
                        return new MockResponse().setResponseCode(404);
                    }
                    try {
                        var body = new ObjectMapper().readTree(request.getBody().readUtf8());
                        if (!"# Evidence\n\nCurrent source body.".equals(body.path("markdown").asText())) {
                            return new MockResponse().setResponseCode(400);
                        }
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                                {"schema_version":"mcp.source-blocks.v1","document_id":"%s","blocks":[
                                 {"block_id":"B0007","ordinal":1,"line_start":1,"line_end":1,"block_type":"heading",
                                  "text":"Evidence","raw_text":"# Evidence"},
                                 {"block_id":"B0008","ordinal":2,"line_start":3,"line_end":3,"block_type":"paragraph",
                                  "text":"Current source body.","raw_text":"Current source body."}]}
                                """.formatted(body.path("document_id").asText()));
                    } catch (Exception e) {
                        return new MockResponse().setResponseCode(500);
                    }
                }
            });
            AI.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.public-key", () -> PUBLIC_KEY);
        registry.add("app.internal.access-base-url", () -> ACCESS.url("/").toString().replaceAll("/$", ""));
        registry.add("app.agent.run-endpoint", () -> AI.url("/internal/agent/run").toString());
    }

    @AfterAll
    static void stopAccess() throws Exception {
        ACCESS.shutdown();
        AI.shutdown();
    }

    @LocalServerPort int port;
    @Autowired DocumentService documents;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    @Test
    void lintCallbackUsesActualDocumentSocketAndExactCurrentPermission() throws Exception {
        redis.opsForValue().set("authz:role:v2:" + WORKSPACE + ":" + USER,
                "{\"role\":\"OWNER\",\"permissions\":[\"wiki.lint\"]}");
        String documentId = documents.createMarkdown(WORKSPACE, USER, "i9-lint-source",
                new MarkdownDocumentCreateRequest("Lint source.md", "# Evidence\n\nCurrent source body.", null)).id();
        String body = """
                {"schema_version":"mcp.wiki-source.v1","actor":{
                 "invocation_id":"00000000-0000-4000-8000-000000000032","grant_id":"%s",
                 "user_id":"%s","workspace_id":"%s","client_id":"%s","policy_generation":2,
                 "origin":"MCP_CLIENT","spec_version":"2025-11-25","tool_name":"submit_lint_changes"}}
                """.formatted(GRANT, USER, WORKSPACE, CLIENT);

        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/documents/" + documentId + "/mcp-wiki-source"))
                .header("Content-Type", "application/json")
                .header("X-Agent-Service-Token", "i9-agent-token")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var source = mapper.readTree(response.body());
        assertThat(source.path("schema_version").asText()).isEqualTo("mcp.wiki-source.v1");
        assertThat(source.path("document_id").asText()).isEqualTo(documentId);
        assertThat(source.path("workspace_id").asText()).isEqualTo(WORKSPACE);
        assertThat(source.path("source_version").asLong()).isOne();
        assertThat(source.path("source_content_hash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(source.path("blocks")).hasSize(2);
        assertThat(source.path("blocks").get(1).path("block_id").asText()).isEqualTo("B0008");
        assertThat(source.path("blocks").get(1).path("line_start").asInt()).isEqualTo(3);
        assertThat(source.path("blocks").get(1).path("block_type").asText()).isEqualTo("paragraph");
        var authorization = ACCESS.takeRequest();
        assertThat(authorization.getHeader("Authorization")).isNull();
        assertThat(authorization.getBody().readUtf8())
                .contains("\"phase\":\"PROPOSAL_CREATE\"")
                .contains("\"required_permissions\":[\"wiki.lint\"]")
                .doesNotContain("wiki.ingest");
    }
}
