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
        return post(path, body, properties.maxResponseBytes());
    }

    /** 응답이 원문보다 커지는 호출(예: source block 해석은 블록마다 text·raw_text를 함께 준다)이 자기 한도를 쓴다. */
    public JsonNode post(String path, Object body, int responseLimit) {
        return exchange(client.post().uri(base + path).contentType(MediaType.APPLICATION_JSON).body(body),
                responseLimit);
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
        return exchange(request, properties.maxResponseBytes());
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request, int limit) {
        try {
            return request.exchange((ignored, response) -> {
                byte[] raw = readBounded(response.getBody(), response.getHeaders().getContentLength(), limit);
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

    private static byte[] readBounded(InputStream input, long length, int limit) throws IOException {
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
                // 공개 본문·게시 객체가 원장과 모순(재시도로 풀리지 않음). 일시 대기는 LEDGER_RECONCILIATION_PENDING이다.
                "LEDGER_CONTRADICTION",
                "CANCELLED", "PURGED", "AUTHZ_UNAVAILABLE",
                // MCP PDF 변환 세션(/internal/mcp/pdf-conversions)
                "CONVERSION_NOT_READY", "STALE_WINDOW", "ATTEMPTS_EXHAUSTED", "WINDOWS_NOT_SUBMITTED",
                "CONVERSION_FINALIZED", "SOURCE_LIMIT_EXCEEDED",
                // MCP wiki draft 거절 코드(pipeline mcp_wiki_draft의 McpWikiDraftError). 클라이언트가 고쳐 다시 보낼 수 있다.
                "INVALID_EVIDENCE", "LEXICAL_OVERLAP_REJECTED", "INSTRUCTION_PATTERN_REJECTED", "UNSAFE_CONTENT",
                "INVALID_LINT_OPERATION", "NOT_FOUND", "EXPIRED", "MERGE_TARGET_CHANGED", "TARGET_CHANGED",
                "SNAPSHOT_MISMATCH", "WINDOW_OUT_OF_ORDER", "WINDOW_ALREADY_SUBMITTED", "INCOMPLETE_WINDOWS",
                "NO_WINDOW_AVAILABLE", "ACTIVE_LIMIT_EXCEEDED", "REQUEST_TOO_LARGE", "RESPONSE_LIMIT_EXCEEDED",
                // 현재 문서 내용이 이미 편입됨(웹·MCP 공통 마지막 성공 편입 해시). 문서를 고친 뒤 다시 시작한다.
                "ALREADY_INGESTED",
                // 실행이 더 이상 받을 수 없는 상태면 pipeline은 run/draft 상태 이름을 code로 돌려준다.
                "READY_TO_FINALIZE", "AWAITING_WEB_APPROVAL", "APPLYING", "PUBLISHED", "NO_CHANGE", "CONFLICTED",
                "HALTED", "UNKNOWN", "INVALIDATED",
                // pipeline이 그대로 전달하는 Document source callback 거절
                "INVALID_SOURCE_TYPE");
        return new McpToolException(allowed.contains(code) ? code : "DEPENDENCY_UNAVAILABLE");
    }
}
