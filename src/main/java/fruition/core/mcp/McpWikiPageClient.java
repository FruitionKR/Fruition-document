package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.service.AiMarkdownSanitizer;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiPageClient {
    private static final Pattern PAGE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,254}");
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "schema_version", "page_id", "page_type", "title", "body", "content_hash", "data_boundary");
    private static final Set<String> ERROR_FIELDS = Set.of(
            "code", "message", "retryable", "handle", "state", "operation_error");
    private final RestClient client;
    private final ObjectMapper mapper;
    private final McpProperties properties;

    public McpWikiPageClient(PipelineClientFactory factory, ObjectMapper mapper, McpProperties properties) {
        this.client = factory.restClient(properties.dependencyTimeoutSeconds());
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.properties = properties;
    }

    public Response get(McpIdentity identity, String pageId) {
        try {
            if (identity == null || identity.workspaceId() == null || identity.workspaceId().isBlank()
                    || !validPageId(pageId)) {
                throw new McpToolException("INVALID_INPUT");
            }
            URI uri = UriComponentsBuilder.fromUri(properties.wikiSearchUri())
                    .replacePath("/wiki/pages").replaceQuery(null).pathSegment(pageId)
                    .queryParam("workspace_id", identity.workspaceId()).queryParam("mcp_read", true)
                    .build().encode().toUri();
            return client.get().uri(uri).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                try {
                    byte[] raw = readBounded(response.getBody(), response.getHeaders().getContentLength());
                    MediaType contentType = response.getHeaders().getContentType();
                    if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                        throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                    }
                    if (response.getStatusCode().value() == 200) return validate(pageId, raw);
                    throw dependencyError(response.getStatusCode().value(), raw);
                } catch (McpToolException e) {
                    throw e;
                } catch (Exception e) {
                    throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                }
            });
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }

    private byte[] readBounded(InputStream input, long contentLength) throws Exception {
        int limit = properties.maxResponseBytes();
        if (contentLength > limit) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        byte[] raw = input.readNBytes(limit);
        if (input.read() != -1) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        return raw;
    }

    private Response validate(String requestedPageId, byte[] raw) throws Exception {
        JsonNode node = mapper.readTree(raw);
        if (!hasExactly(node, RESPONSE_FIELDS)
                || RESPONSE_FIELDS.stream().anyMatch(field -> !node.path(field).isTextual())) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        Response response = mapper.treeToValue(node, Response.class);
        if (!"mcp.wiki-page.v1".equals(response.schemaVersion)
                || !requestedPageId.equals(response.pageId)
                || !("SOURCE".equals(response.pageType) || "CONCEPT".equals(response.pageType))
                || response.title == null || response.body == null
                || !"UNTRUSTED_WORKSPACE_CONTENT".equals(response.dataBoundary)
                || response.contentHash == null || !HASH.matcher(response.contentHash).matches()) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        byte[] body = utf8(response.body);
        if (body.length > properties.maxDocumentContentBytes()) {
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        }
        if (!response.contentHash.equals("sha256:" + sha256(body))) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        String title = AiMarkdownSanitizer.sanitize(response.title);
        String sanitizedBody = AiMarkdownSanitizer.sanitize(response.body);
        byte[] sanitizedBodyBytes = utf8(sanitizedBody);
        if (sanitizedBodyBytes.length > properties.maxDocumentContentBytes()) {
            throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        }
        // Public content identity covers the exact neutralized body returned to the MCP client, not internal storage.
        return new Response(response.schemaVersion, response.pageId, response.pageType, title, sanitizedBody,
                "sha256:" + sha256(sanitizedBodyBytes), response.dataBoundary);
    }

    static boolean validPageId(String pageId) {
        return PAGE_ID.matcher(pageId == null ? "" : pageId).matches();
    }

    private McpToolException dependencyError(int status, byte[] raw) throws Exception {
        JsonNode root = mapper.readTree(raw);
        JsonNode error = root == null ? null : root.get("error");
        if (!hasExactly(root, Set.of("error")) || !hasExactly(error, ERROR_FIELDS)
                || !error.path("code").isTextual() || !error.path("message").isTextual()
                || error.path("message").textValue().isBlank() || !error.path("retryable").isBoolean()
                || !error.path("handle").isNull() || !error.path("state").isNull()
                || !error.path("operation_error").isNull()) {
            return new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        String code = error.path("code").textValue();
        boolean valid = (status == 400 && "INVALID_INPUT".equals(code))
                || (status == 404 && "PAGE_NOT_FOUND".equals(code))
                || (status == 409 && "PAGE_CHANGED".equals(code))
                || (status == 413 && "RESPONSE_LIMIT_EXCEEDED".equals(code))
                || (status == 503 && "DEPENDENCY_UNAVAILABLE".equals(code));
        return new McpToolException(valid ? code : "DEPENDENCY_UNAVAILABLE");
    }

    private static boolean hasExactly(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) return false;
        for (String field : fields) if (!node.has(field)) return false;
        return true;
    }

    private static byte[] utf8(String value) throws CharacterCodingException {
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public record Response(@JsonProperty("schema_version") String schemaVersion,
                           @JsonProperty("page_id") String pageId,
                           @JsonProperty("page_type") String pageType,
                           String title, String body,
                           @JsonProperty("content_hash") String contentHash,
                           @JsonProperty("data_boundary") String dataBoundary) {}
}
