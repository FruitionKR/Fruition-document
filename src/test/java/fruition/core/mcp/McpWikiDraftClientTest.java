package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpWikiDraftClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    // pipeline mcp_wiki_draft가 실제로 내는 거절 코드는 서버 장애로 바꾸지 않고 그대로 전달한다.
    @ParameterizedTest
    @CsvSource({"400,INVALID_EVIDENCE", "400,LEXICAL_OVERLAP_REJECTED", "400,INSTRUCTION_PATTERN_REJECTED",
            "400,UNSAFE_CONTENT", "409,INVALID_LINT_OPERATION", "404,NOT_FOUND", "410,EXPIRED",
            "409,MERGE_TARGET_CHANGED", "409,TARGET_CHANGED", "409,SNAPSHOT_MISMATCH", "409,WINDOW_OUT_OF_ORDER",
            "409,WINDOW_ALREADY_SUBMITTED", "409,INCOMPLETE_WINDOWS", "409,NO_WINDOW_AVAILABLE",
            "409,ACTIVE_LIMIT_EXCEEDED", "413,REQUEST_TOO_LARGE", "413,RESPONSE_LIMIT_EXCEEDED",
            "409,READY_TO_FINALIZE", "409,AWAITING_WEB_APPROVAL", "410,INVALIDATED", "400,INVALID_SOURCE_TYPE"})
    void passesPipelineRejectionCodesThrough(int status, String code) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(error(status, code));
            server.start();
            assertThatThrownBy(() -> client(server).post("/internal/mcp/wiki/ingest-runs", Map.of()))
                    .isInstanceOf(McpToolException.class).hasMessage(code);
        }
    }

    @Test
    void masksUnknownCodesAndBodiesWithoutErrorCodeAsDependencyUnavailable() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(error(400, "SOMETHING_NEW"));
            // FastAPI 기본 422는 Document가 만든 header/query 계약 위반이라 클라이언트가 고칠 수 없다.
            server.enqueue(new MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                    .setBody("{\"detail\":[{\"loc\":[\"header\",\"x-mcp-user-id\"],\"type\":\"missing\"}]}"));
            server.start();
            var client = client(server);
            assertThatThrownBy(() -> client.post("/internal/mcp/wiki/ingest-runs", Map.of()))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
            assertThatThrownBy(() -> client.post("/internal/mcp/wiki/ingest-runs", Map.of()))
                    .isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
        }
    }

    private McpWikiDraftClient client(MockWebServer server) {
        var properties = new McpProperties(URI.create("https://mcp.example/mcp"),
                URI.create("https://auth.example"), List.of(URI.create("https://client.example")),
                server.url("/internal/mcp/wiki/search").uri(), "x".repeat(32).getBytes(StandardCharsets.UTF_8),
                10, 20, 100_000, 4096, 100, 100_000, 10, 10_000, 5);
        return new McpWikiDraftClient(new PipelineClientFactory("internal"), mapper, properties,
                server.url("/agent/runs").toString(), "agent-token");
    }

    private MockResponse error(int status, String code) throws Exception {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of("error", Map.of("code", code, "message", "Safe error",
                        "retryable", false))));
    }
}
