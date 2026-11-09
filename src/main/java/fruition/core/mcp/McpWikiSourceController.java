package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiSourceController {
    private static final Pattern DOCUMENT_ID = Pattern.compile("(?:doc_|chatdoc_)[A-Za-z0-9][A-Za-z0-9_-]{0,246}");
    private final McpWikiSourceService service;
    private final McpProperties properties;
    private final ObjectMapper strictMapper;

    public McpWikiSourceController(McpWikiSourceService service, McpProperties properties, ObjectMapper mapper) {
        this.service = service;
        this.properties = properties;
        this.strictMapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Operation(operationId = "readMcpWikiSource",
            summary = "MCP wiki draft의 현재 Document source callback")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Request.class)))
    @PostMapping("/internal/documents/{document_id}/mcp-wiki-source")
    public ResponseEntity<?> read(@PathVariable("document_id") String documentId,
                                  HttpServletRequest servletRequest) {
        Request request;
        try {
            byte[] raw = servletRequest.getInputStream().readNBytes(properties.maxResponseBytes() + 1);
            if (raw.length > properties.maxResponseBytes()) return error(413, "REQUEST_TOO_LARGE", false);
            request = strictMapper.readValue(raw, Request.class);
            validate(documentId, request);
        } catch (Exception e) {
            return error(400, "INVALID_INPUT", false);
        }
        try {
            return ResponseEntity.ok(service.read(request.actor(), documentId));
        } catch (McpAuthorizationException e) {
            return error(e.status(), e.code(), e.status() >= 500);
        } catch (Exception e) {
            return error(503, "DEPENDENCY_UNAVAILABLE", true);
        }
    }

    private static void validate(String documentId, Request request) {
        if (!DOCUMENT_ID.matcher(documentId).matches() || request == null
                || !"mcp.wiki-source.v1".equals(request.schemaVersion()) || request.actor() == null) invalid();
        McpProposalActor actor = request.actor();
        if (!canonicalV4(actor.invocationId()) || !canonicalV4(actor.grantId())
                || blank(actor.userId()) || blank(actor.workspaceId()) || !cleanHttps(actor.clientId())
                || actor.policyGeneration() < 1 || !"MCP_CLIENT".equals(actor.origin())
                || !"2025-11-25".equals(actor.specVersion())
                || !java.util.Set.of("start_ingest", "submit_lint_changes").contains(actor.toolName())) invalid();
    }

    private static boolean canonicalV4(String value) {
        try { UUID parsed = UUID.fromString(value); return parsed.version() == 4 && parsed.toString().equals(value); }
        catch (RuntimeException e) { return false; }
    }
    private static boolean cleanHttps(String value) {
        try {
            if (blank(value) || !value.equals(value.trim()) || value.length() > 2048) return false;
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (RuntimeException e) { return false; }
    }
    private static boolean blank(String value) { return value == null || value.isBlank() || value.length() > 255; }
    private static void invalid() { throw new IllegalArgumentException("invalid source request"); }
    private static ResponseEntity<ErrorEnvelope> error(int status, String code, boolean retryable) {
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(new ErrorEnvelope(
                new ErrorBody(code, "MCP wiki source request was rejected.", retryable, null, null, null)));
    }

    @Schema(name = "McpWikiSourceRequest")
    public record Request(@JsonProperty("schema_version") String schemaVersion, McpProposalActor actor) {}
    public record ErrorEnvelope(ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String handle, String state,
                            @JsonProperty("operation_error") Object operationError) {}
}
