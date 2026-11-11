package fruition.core.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public record McpProperties(
        URI resourceUri, URI authorizationServerUri, List<URI> allowedOrigins, URI wikiSearchUri,
        byte[] cursorSecret, int maxPageSize, int maxCandidates, int maxResponseBytes,
        int maxCursorBytes, int maxSourceBlocks, int maxSourceBytes,
        int maxDocumentBlocksPerPage, int maxDocumentContentBytes, int dependencyTimeoutSeconds) {

    @Autowired
    public McpProperties(
            @Value("${app.mcp.resource-uri}") String resourceUri,
            @Value("${app.mcp.authorization-server-uri}") String authorizationServerUri,
            @Value("${app.mcp.allowed-origins}") List<String> origins,
            @Value("${app.mcp.wiki-search-uri}") String wikiSearchUri,
            @Value("${app.mcp.cursor-secret}") String cursorSecret,
            @Value("${app.mcp.max-page-size}") int maxPageSize,
            @Value("${app.mcp.max-candidates}") int maxCandidates,
            @Value("${app.mcp.max-response-bytes}") int maxResponseBytes,
            @Value("${app.mcp.max-cursor-bytes}") int maxCursorBytes,
            @Value("${app.mcp.max-source-blocks}") int maxSourceBlocks,
            @Value("${app.mcp.max-source-bytes}") int maxSourceBytes,
            @Value("${app.mcp.max-document-blocks-per-page}") int maxDocumentBlocksPerPage,
            @Value("${app.mcp.max-document-content-bytes}") int maxDocumentContentBytes,
            @Value("${app.mcp.dependency-timeout-seconds}") int dependencyTimeoutSeconds) {
        this(parseHttps(resourceUri, true), parseHttps(authorizationServerUri, false),
                origins.stream().map(o -> parseOrigin(o.trim())).toList(), parseInternal(wikiSearchUri),
                cursorSecret.getBytes(StandardCharsets.UTF_8), maxPageSize, maxCandidates, maxResponseBytes,
                maxCursorBytes, maxSourceBlocks, maxSourceBytes, maxDocumentBlocksPerPage,
                maxDocumentContentBytes, dependencyTimeoutSeconds);
        if (allowedOrigins.isEmpty() || this.cursorSecret.length < 32
                || maxPageSize < 1 || maxCandidates < maxPageSize || maxResponseBytes < 1
                || maxCursorBytes < 64 || maxSourceBlocks < 1 || maxSourceBytes < 1
                || maxDocumentBlocksPerPage < 1 || maxDocumentContentBytes < 1
                || dependencyTimeoutSeconds < 1) {
            throw new IllegalArgumentException("MCP deployment limits, origins and cursor secret must be explicit");
        }
    }

    private static URI parseHttps(String value, boolean resource) {
        return parse(value, resource, false, "MCP URIs must be canonical HTTPS URIs");
    }

    /** 위키 검색은 클러스터 내부 ai 서비스(http://pipeline-api:8000)로만 닿으므로 http도 허용한다. */
    private static URI parseInternal(String value) {
        return parse(value, false, true, "MCP wiki search URI must be a canonical HTTP(S) URI");
    }

    private static URI parse(String value, boolean resource, boolean allowHttp, String message) {
        try {
            URI uri = URI.create(value);
            boolean scheme = "https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme()));
            if (!scheme || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (resource && !"/mcp".equals(uri.getPath()))) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(message);
        }
    }

    static URI parseOrigin(String value) {
        URI uri = parseHttps(value, false);
        if (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
            throw new IllegalArgumentException("MCP origins cannot contain paths");
        return URI.create("https://" + uri.getAuthority());
    }

    public String metadataUri() {
        return "https://" + resourceUri.getAuthority() + "/.well-known/oauth-protected-resource/mcp";
    }
}
