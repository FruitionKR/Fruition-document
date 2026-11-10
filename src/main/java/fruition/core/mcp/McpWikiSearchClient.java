package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.core.document.service.DocumentAccessPolicy;
import fruition.core.document.service.McpDocumentDataGateway;
import fruition.core.usage.service.CreditService;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiSearchClient {
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern DOCUMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,254}");
    private static final Pattern BLOCK_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_:-]{0,254}");
    private final RestClient client;
    private final ObjectMapper mapper;
    private final McpProperties properties;
    private final McpDocumentDataGateway documents;
    private final CreditService credits;

    public McpWikiSearchClient(PipelineClientFactory factory, ObjectMapper mapper, McpProperties properties,
                               McpDocumentDataGateway documents, CreditService credits) {
        this.client = factory.restClient(properties.dependencyTimeoutSeconds());
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.properties = properties; this.documents = documents; this.credits = credits;
    }

    public Response search(McpIdentity id, String query, String cursor, int pageSize) {
        try {
            if (query == null || query.isBlank() || query.getBytes(StandardCharsets.UTF_8).length > properties.maxResponseBytes()
                    || pageSize < 1 || pageSize > properties.maxPageSize()
                    || (cursor != null && cursor.getBytes(StandardCharsets.UTF_8).length > properties.maxCursorBytes()))
                throw new McpToolException("INVALID_INPUT");
            Request request = new Request("mcp.wiki-search.v1", new Actor(UUID.randomUUID().toString(), id.grantId(),
                    id.userId(), id.workspaceId(), id.clientId(), id.policyGeneration(), "MCP_CLIENT", "2025-11-25",
                    "search_wiki"), query, cursor, pageSize, credits.paidAssistAllowed(id.userId()));
            byte[] payload = mapper.writeValueAsBytes(request);
            String raw = client.post().uri(properties.wikiSearchUri()).contentType(MediaType.APPLICATION_JSON)
                    .contentLength(payload.length).body(payload).retrieve().body(String.class);
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > properties.maxResponseBytes())
                throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
            Response response = mapper.readValue(raw, Response.class);
            Response validated = validate(id, response);
            if (mapper.writeValueAsBytes(validated).length > properties.maxResponseBytes())
                throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
            return validated;
        } catch (McpToolException e) { throw e; }
        catch (Exception e) { throw new McpToolException("DEPENDENCY_UNAVAILABLE"); }
    }

    private Response validate(McpIdentity id, Response r) {
        if (!"mcp.wiki-search.v1".equals(r.schemaVersion) || !"UNTRUSTED_WORKSPACE_CONTENT".equals(r.dataBoundary)
                || r.results == null || r.results.size() > properties.maxPageSize()
                || r.truncated != (r.nextCursor != null)) throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        if (r.nextCursor != null
                && r.nextCursor.getBytes(StandardCharsets.UTF_8).length > properties.maxCursorBytes())
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        DocumentAccessPolicy.Viewer viewer = documents.viewer(id.workspaceId(), id.userId(), id.owner(), id.canConvert());
        Set<String> pages = new HashSet<>();
        java.util.ArrayList<Result> sanitized = new java.util.ArrayList<>(r.results.size());
        for (Result result : r.results) {
            if (blank(result.pageId) || !pages.add(result.pageId) || blank(result.title) || blank(result.snippet)
                    || !("SOURCE".equals(result.pageType) || "CONCEPT".equals(result.pageType))
                    || result.version < 1 || !HASH.matcher(result.contentHash).matches() || result.evidence == null)
                throw new McpToolException("DEPENDENCY_UNAVAILABLE");
            Set<String> locators = new HashSet<>();
            for (Evidence evidence : result.evidence) {
                if (blank(evidence.documentId) || !DOCUMENT_ID.matcher(evidence.documentId).matches()
                        || blank(evidence.blockId) || !BLOCK_ID.matcher(evidence.blockId).matches()
                        || !locators.add(evidence.documentId + "\u0000" + evidence.blockId))
                    throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                documents.find(id.workspaceId(), evidence.documentId)
                        .filter(viewer::canRead).orElseThrow(() -> new McpToolException("DEPENDENCY_UNAVAILABLE"));
            }
            sanitized.add(new Result(result.pageId, result.pageType,
                    AiMarkdownSanitizer.sanitize(result.title), result.version, result.contentHash,
                    AiMarkdownSanitizer.sanitize(result.snippet), result.evidence));
        }
        return new Response(r.schemaVersion, List.copyOf(sanitized), r.nextCursor, r.truncated, r.dataBoundary);
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    record Request(@JsonProperty("schema_version") String schemaVersion, Actor actor, String query,
                   String cursor, @JsonProperty("page_size") int pageSize,
                   @JsonProperty("jev_allowed") boolean jevAllowed) {}
    record Actor(@JsonProperty("invocation_id") String invocationId, @JsonProperty("grant_id") String grantId,
                 @JsonProperty("user_id") String userId, @JsonProperty("workspace_id") String workspaceId,
                 @JsonProperty("client_id") String clientId, @JsonProperty("policy_generation") long policyGeneration,
                 String origin, @JsonProperty("spec_version") String specVersion,
                 @JsonProperty("tool_name") String toolName) {}
    public record Response(@JsonProperty("schema_version") String schemaVersion, List<Result> results,
                           @JsonProperty("next_cursor") String nextCursor, boolean truncated,
                           @JsonProperty("data_boundary") String dataBoundary) {}
    public record Result(@JsonProperty("page_id") String pageId, @JsonProperty("page_type") String pageType,
                         String title, long version, @JsonProperty("content_hash") String contentHash,
                         String snippet, List<Evidence> evidence) {}
    public record Evidence(@JsonProperty("document_id") String documentId,
                           @JsonProperty("block_id") String blockId) {}
}
