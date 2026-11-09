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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/internal/agent/mcp")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpDocumentEditContextController {
    private final McpDocumentEditContextService service;
    private final McpProperties properties;
    private final ObjectMapper strictMapper;

    public McpDocumentEditContextController(McpDocumentEditContextService service, McpProperties properties,
                                            ObjectMapper mapper) {
        this.service = service;
        this.properties = properties;
        this.strictMapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Operation(operationId = "readMcpDocumentEditContext",
            summary = "MCP 문서 편집 제안의 현재 본문 조회")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Request.class)))
    @PostMapping("/document-edit-context")
    public ResponseEntity<?> read(HttpServletRequest servletRequest) {
        Request request;
        try {
            byte[] raw = servletRequest.getInputStream().readNBytes(properties.maxResponseBytes() + 1);
            if (raw.length > properties.maxResponseBytes()) {
                return error(413, "REQUEST_TOO_LARGE", false);
            }
            request = strictMapper.readValue(raw, Request.class);
            validate(request);
        } catch (Exception e) {
            return error(400, "INVALID_INPUT", false);
        }
        try {
            return ResponseEntity.ok(service.read(request.actor(), request.documentId(), request.baseVersion()));
        } catch (McpAuthorizationException e) {
            return error(e.status(), e.code(), e.status() >= 500);
        } catch (Exception e) {
            return error(503, "DEPENDENCY_UNAVAILABLE", true);
        }
    }

    private static void validate(Request request) {
        if (request == null || !"mcp.document-edit-context.v1".equals(request.schemaVersion())
                || request.actor() == null || request.documentId() == null || request.baseVersion() < 1) {
            throw new IllegalArgumentException("invalid context request");
        }
        McpProposalActor actor = request.actor();
        if (!canonicalV4(actor.invocationId()) || !canonicalV4(actor.grantId())
                || blank(actor.userId()) || blank(actor.workspaceId()) || !cleanHttps(actor.clientId())
                || actor.policyGeneration() < 1 || !"MCP_CLIENT".equals(actor.origin())
                || !"2025-11-25".equals(actor.specVersion())
                || !"propose_document_edit".equals(actor.toolName())) {
            throw new IllegalArgumentException("invalid proposal actor");
        }
    }

    private static boolean canonicalV4(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.version() == 4 && parsed.toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean cleanHttps(String value) {
        try {
            if (blank(value) || !value.equals(value.trim()) || value.length() > 2048) return false;
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 255;
    }

    private static ResponseEntity<ErrorEnvelope> error(int status, String code, boolean retryable) {
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(new ErrorEnvelope(
                new ErrorBody(code, "Document edit context request was rejected.", retryable,
                        null, null, null)));
    }

    @Schema(name = "McpDocumentEditContextRequest")
    public record Request(
            @JsonProperty("schema_version") String schemaVersion,
            McpProposalActor actor,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") long baseVersion) {}
    public record ErrorEnvelope(ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String handle, String state,
                            @JsonProperty("operation_error") Object operationError) {}
}
