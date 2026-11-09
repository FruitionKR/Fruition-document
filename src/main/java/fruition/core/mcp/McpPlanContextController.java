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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/agent/mcp")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpPlanContextController {
    private final McpPlanContextService service;
    private final McpProperties properties;
    private final ObjectMapper strictMapper;

    public McpPlanContextController(McpPlanContextService service, McpProperties properties, ObjectMapper mapper) {
        this.service = service;
        this.properties = properties;
        this.strictMapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Operation(operationId = "readMcpPlanContext", summary = "MCP 계획 제안의 현재 문서 계층과 편집 본문 조회")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Request.class)))
    @PostMapping("/plan-context")
    public ResponseEntity<?> read(HttpServletRequest servletRequest) {
        Request request;
        try {
            byte[] raw = servletRequest.getInputStream().readNBytes(properties.maxResponseBytes() + 1);
            if (raw.length > properties.maxResponseBytes()) return error(413, "REQUEST_TOO_LARGE", false);
            request = strictMapper.readValue(raw, Request.class);
            validate(request);
        } catch (Exception e) {
            return error(400, "INVALID_INPUT", false);
        }
        try {
            return ResponseEntity.ok(service.read(request.actor(), request.targetFolderId(),
                    request.resources(), request.editDocuments()));
        } catch (McpAuthorizationException e) {
            return error(e.status(), e.code(), e.status() >= 500);
        } catch (Exception e) {
            return error(503, "DEPENDENCY_UNAVAILABLE", true);
        }
    }

    private static void validate(Request request) {
        if (request == null || !"mcp.plan-context.v1".equals(request.schemaVersion())
                || request.actor() == null || request.resources() == null || request.editDocuments() == null) {
            throw new IllegalArgumentException();
        }
        McpProposalActor actor = request.actor();
        if (blank(actor.invocationId()) || blank(actor.grantId()) || blank(actor.userId())
                || blank(actor.workspaceId()) || blank(actor.clientId()) || actor.policyGeneration() < 1
                || !"MCP_CLIENT".equals(actor.origin()) || !"2025-11-25".equals(actor.specVersion())
                || !"propose_plan".equals(actor.toolName())) throw new IllegalArgumentException();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ResponseEntity<?> error(int status, String code, boolean retryable) {
        return ResponseEntity.status(status).body(java.util.Map.of("error", java.util.Map.of(
                "code", code, "message", code, "retryable", retryable)));
    }

    @Schema(name = "McpPlanContextRequest")
    public record Request(@JsonProperty("schema_version") String schemaVersion, McpProposalActor actor,
                          @JsonProperty("target_folder_id") String targetFolderId,
                          List<McpPlanContextService.Resource> resources,
                          @JsonProperty("edit_documents") List<McpPlanContextService.EditDocument> editDocuments) {}
}
