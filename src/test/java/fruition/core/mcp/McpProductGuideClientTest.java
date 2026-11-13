package fruition.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpProductGuideClientTest {
    private McpProductGuideClient client(MockWebServer server) {
        var properties = mock(McpProperties.class);
        when(properties.wikiSearchUri()).thenReturn(server.url("/internal/mcp/wiki/search").uri());
        when(properties.maxResponseBytes()).thenReturn(100_000);
        when(properties.dependencyTimeoutSeconds()).thenReturn(2);
        return new McpProductGuideClient(new PipelineClientFactory("internal-test"), new ObjectMapper(), properties);
    }

    @Test void readsPublicGuideWithInternalTokenAndWithoutUserPayload() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                    "{\"schema_version\":\"mcp.product-guide.v1\",\"title\":\"Guide\",\"markdown\":\"위키 편입\"}"));
            server.start();
            var result = client(server).read();
            assertThat(result.markdown()).isEqualTo("위키 편입");
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/internal/mcp/product-guide");
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getHeader("X-Internal-Token")).isEqualTo("internal-test");
            assertThat(request.getHeader("Authorization")).isNull();
            assertThat(request.getBodySize()).isZero();
        }
    }

    @Test void rejectsErrorsMalformedOrOversizedGuides() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var client = client(server);
            for (String body : new String[] {"{}", "null", "not json",
                    "{\"schema_version\":\"wrong\",\"title\":\"Guide\",\"markdown\":\"text\"}",
                    "{\"schema_version\":\"mcp.product-guide.v1\",\"title\":\"Guide\",\"markdown\":7}",
                    "{\"schema_version\":\"mcp.product-guide.v1\",\"title\":\"Guide\",\"markdown\":\"text\",\"system_prompt\":\"private\"}"}) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
                assertThatThrownBy(client::read).isInstanceOf(McpToolException.class).hasMessage("DEPENDENCY_UNAVAILABLE");
            }
            server.enqueue(new MockResponse().setResponseCode(503));
            assertThatThrownBy(client::read).hasMessage("DEPENDENCY_UNAVAILABLE");
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setChunkedBody("x".repeat(100_001), 1000));
            assertThatThrownBy(client::read).hasMessage("RESPONSE_LIMIT_EXCEEDED");
        }
    }
}
