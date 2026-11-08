package fruition.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpAgentProposalClient {
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "schema_version", "run_id", "plan_id", "plan_version", "operation_hash", "status");
    private static final Set<String> ERROR_FIELDS = Set.of(
            "code", "message", "retryable", "handle", "state", "operation_error");
    private static final Pattern OPERATION_HASH = Pattern.compile("[0-9a-f]{64}");
    private final RestClient client;
    private final ObjectMapper mapper;
    private final McpProperties properties;
    private final String endpoint;

    public McpAgentProposalClient(PipelineClientFactory factory, ObjectMapper mapper, McpProperties properties,
                                  @Value("${app.agent.run-endpoint}") String runEndpoint,
                                  @Value("${app.skill.agent-token}") String agentToken) {
        this.client = RestClient.builder()
                .requestFactory(factory.requestFactory(properties.dependencyTimeoutSeconds()))
                .defaultHeader("X-Agent-Service-Token", agentToken)
                .build();
        this.mapper = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.properties = properties;
        this.endpoint = runEndpoint.replaceAll("/+$", "") + "/mcp-proposals";
    }

    public Response create(Map<String, Object> request) {
        try {
            return client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((ignored, response) -> {
                        byte[] raw = readBounded(response.getBody(), response.getHeaders().getContentLength());
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                        }
                        if (response.getStatusCode().value() == 200) return validate(raw);
                        throw error(response.getStatusCode().value(), raw);
                    });
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }

    private byte[] readBounded(InputStream input, long contentLength) throws IOException {
        int limit = properties.maxResponseBytes();
        if (contentLength > limit) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        byte[] raw = input.readNBytes(limit);
        if (input.read() != -1) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        return raw;
    }

    private Response validate(byte[] raw) throws IOException {
        JsonNode node = mapper.readTree(raw);
        if (!hasExactly(node, RESPONSE_FIELDS)
                || !node.path("schema_version").isTextual() || !node.path("run_id").isTextual()
                || !node.path("plan_id").isTextual() || !node.path("plan_version").isIntegralNumber()
                || !node.path("operation_hash").isTextual() || !node.path("status").isTextual()) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        Response response = mapper.treeToValue(node, Response.class);
        if (!"mcp.agent-proposal.v1".equals(response.schemaVersion)
                || !canonicalUuid4(response.runId) || !canonicalUuid4(response.planId)
                || response.planVersion != 1 || !OPERATION_HASH.matcher(response.operationHash).matches()
                || !"awaiting_approval".equals(response.status)) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        return response;
    }

    private McpToolException error(int status, byte[] raw) throws IOException {
        JsonNode root = mapper.readTree(raw);
        JsonNode error = root == null ? null : root.get("error");
        if (!hasExactly(root, Set.of("error")) || !hasExactly(error, ERROR_FIELDS)
                || !error.path("code").isTextual() || !error.path("message").isTextual()
                || error.path("message").textValue().isBlank() || !error.path("retryable").isBoolean()
                || !error.path("handle").isNull() || !error.path("state").isNull()) {
            return new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        String code = error.path("code").textValue();
        boolean valid = (status == 400 && Set.of("INVALID_INPUT", "UNSUPPORTED_TOOL", "OBVIOUS_NO_OP").contains(code))
                || (status == 403 && "PERMISSION_REVOKED".equals(code))
                || (status == 404 && "RESOURCE_NOT_FOUND".equals(code))
                || (status == 409 && Set.of("IDEMPOTENCY_CONFLICT", "SOURCE_CHANGED").contains(code))
                || (status == 413 && Set.of("REQUEST_TOO_LARGE", "RESPONSE_LIMIT_EXCEEDED").contains(code))
                || (status == 503 && "DEPENDENCY_UNAVAILABLE".equals(code));
        return new McpToolException(valid ? code : "DEPENDENCY_UNAVAILABLE");
    }

    private static boolean hasExactly(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) return false;
        for (String field : fields) if (!node.has(field)) return false;
        return true;
    }

    private static boolean canonicalUuid4(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.version() == 4 && uuid.toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public record Response(@com.fasterxml.jackson.annotation.JsonProperty("schema_version") String schemaVersion,
                           @com.fasterxml.jackson.annotation.JsonProperty("run_id") String runId,
                           @com.fasterxml.jackson.annotation.JsonProperty("plan_id") String planId,
                           @com.fasterxml.jackson.annotation.JsonProperty("plan_version") long planVersion,
                           @com.fasterxml.jackson.annotation.JsonProperty("operation_hash") String operationHash,
                           String status) {}
}
