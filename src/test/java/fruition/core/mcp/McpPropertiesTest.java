package fruition.core.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://pipeline-api:8000/internal/mcp/wiki/search",
            "https://ai.example/internal/mcp/wiki/search"})
    void wikiSearchUriAcceptsInClusterHttpAndHttps(String uri) {
        assertThat(props("https://mcp.example/mcp", "https://auth.example", "https://client.example", uri)
                .wikiSearchUri().toString()).isEqualTo(uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://pipeline-api/internal/mcp/wiki/search",
            "http://user:pw@pipeline-api:8000/internal/mcp/wiki/search",
            "http://pipeline-api:8000/internal/mcp/wiki/search?x=1",
            "http://pipeline-api:8000/internal/mcp/wiki/search#frag",
            "http:/internal/mcp/wiki/search", "/internal/mcp/wiki/search"})
    void wikiSearchUriStillRejectsNonCanonicalUris(String uri) {
        assertThatThrownBy(() -> props("https://mcp.example/mcp", "https://auth.example", "https://client.example", uri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MCP wiki search URI must be a canonical HTTP(S) URI");
    }

    @Test
    void resourceAuthorizationServerAndOriginsStayHttpsOnly() {
        String wiki = "http://pipeline-api:8000/internal/mcp/wiki/search";
        assertThatThrownBy(() -> props("http://mcp.example/mcp", "https://auth.example", "https://client.example", wiki))
                .hasMessage("MCP URIs must be canonical HTTPS URIs");
        assertThatThrownBy(() -> props("https://mcp.example/mcp", "http://auth.example", "https://client.example", wiki))
                .hasMessage("MCP URIs must be canonical HTTPS URIs");
        assertThatThrownBy(() -> props("https://mcp.example/mcp", "https://auth.example", "http://client.example", wiki))
                .hasMessage("MCP URIs must be canonical HTTPS URIs");
    }

    private static McpProperties props(String resource, String authorization, String origin, String wiki) {
        return new McpProperties(resource, authorization, List.of(origin), wiki, "x".repeat(32),
                10, 20, 100_000, 4096, 100, 100_000, 10, 10_000, 5);
    }
}
