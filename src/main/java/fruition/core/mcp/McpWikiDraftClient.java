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
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Strict service-auth client for the frozen I9 AI boundary. */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpWikiDraftClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final McpProperties properties;
    private final String base;

    public McpWikiDraftClient(PipelineClientFactory factory, ObjectMapper mapper, McpProperties properties,
                              @Value("${app.agent.run-endpoint}") String runEndpoint,
                              @Value("${app.skill.agent-token}") String agentToken) {
        this.client = RestClient.builder()
                .requestFactory(factory.requestFactory(properties.dependencyTimeoutSeconds()))
                .defaultHeader("X-Agent-Service-Token", agentToken).build();
        this.mapper = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.properties = properties;
        URI uri = URI.create(runEndpoint);
        this.base = uri.getScheme() + "://" + uri.getAuthority();
    }

    public JsonNode post(String path, Object body) {
        return exchange(client.post().uri(base + path).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    public JsonNode getPreview(String draftId, long version, String workspaceId, String userId) {
        return exchange(client.get().uri(base + "/internal/mcp/wiki/drafts/{draft}/preview?draft_version={version}",
                        draftId, version)
                .header("X-MCP-Workspace-Id", workspaceId).header("X-MCP-User-Id", userId));
    }

    public JsonNode getResult(String approvalId, String operationId, String workspaceId, String userId) {
        return exchange(client.get().uri(base + "/internal/mcp/wiki/publication-results/{approval}/{operation}",
                        approvalId, operationId)
                .header("X-MCP-Workspace-Id", workspaceId).header("X-MCP-User-Id", userId));
    }

    public JsonNode cancel(String taskId, String workspaceId, String userId) {
        return post("/internal/ai/tasks/" + taskId + "/cancel",
                Map.of("workspace_id", workspaceId, "user_id", userId));
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((ignored, response) -> {
                byte[] raw = readBounded(response.getBody(), response.getHeaders().getContentLength());
                MediaType type = response.getHeaders().getContentType();
                if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                    throw new McpToolException("DEPENDENCY_UNAVAILABLE");
                }
                JsonNode root = mapper.readTree(raw);
                if (response.getStatusCode().is2xxSuccessful()) return root;
                throw mappedError(response.getStatusCode().value(), root);
            });
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
    }

    private byte[] readBounded(InputStream input, long length) throws IOException {
        int limit = properties.maxResponseBytes();
        if (length > limit) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        byte[] raw = input.readNBytes(limit + 1);
        if (raw.length > limit) throw new McpToolException("RESPONSE_LIMIT_EXCEEDED");
        return raw;
    }

    private static McpToolException mappedError(int status, JsonNode root) {
        JsonNode error = root == null ? null : root.get("error");
        if (error == null || !error.isObject() || !error.path("code").isTextual()) {
            return new McpToolException("DEPENDENCY_UNAVAILABLE");
        }
        String code = error.path("code").asText();
        Set<String> allowed = Set.of("INVALID_INPUT", "RESOURCE_NOT_FOUND", "PERMISSION_REVOKED",
                "IDEMPOTENCY_CONFLICT", "SOURCE_CHANGED", "PUBLIC_STATE_CHANGED", "ACTIVE_RUN_EXISTS",
                "PREVIEW_TOO_LARGE", "LEDGER_RECONCILIATION_PENDING", "DEPENDENCY_UNAVAILABLE",
                "CANCELLED", "PURGED", "AUTHZ_UNAVAILABLE");
        return new McpToolException(allowed.contains(code) ? code : "DEPENDENCY_UNAVAILABLE");
    }
}
