package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpAuditLogger {
    private static final Logger AUDIT = LoggerFactory.getLogger("fruition.mcp.audit");
    /** PostHog Logs 전송용 업무 로그. 감사 JSON 소비자와 섞이지 않게 logger를 분리한다. */
    private static final Logger ACTIVITY = LoggerFactory.getLogger("fruition.mcp.activity");
    private static final Pattern SAFE_TARGET = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final int MAX_TARGETS = 20;
    private final ObjectMapper mapper;
    public McpAuditLogger(ObjectMapper mapper) { this.mapper = mapper; }
    public void log(McpIdentity id, String tool, List<String> targets, String result, String errorCode) {
        String auditId = UUID.randomUUID().toString();
        try { AUDIT.info(mapper.writeValueAsString(new Event(auditId, Instant.now(),
                id.userId(), id.workspaceId(), id.clientId(), id.grantId(), tool, targets, result, errorCode,
                "MCP_CLIENT"))); }
        catch (Exception e) { AUDIT.error("MCP_AUDIT_SERIALIZATION_FAILED"); }
        // 요청 입력에서 온 대상 ID는 key=value 주입을 막도록 안전한 형식만 남긴다. 전체 개수는 targetCount로 남긴다.
        String safeTargets = targets.stream().filter(t -> t != null && SAFE_TARGET.matcher(t).matches())
                .limit(MAX_TARGETS).collect(Collectors.joining(","));
        boolean success = "SUCCESS".equals(result);
        String template = (success ? "[MCP 도구 호출 성공]" : "[MCP 도구 호출 실패]")
                + " operation=mcp_tool stage=completed origin=MCP_CLIENT status={} toolName={} errorCode={}"
                + " auditId={} userId={} workspaceId={} grantId={} clientIdHash={} targetCount={} targetIds={}";
        Object[] args = {result, tool, errorCode == null ? "-" : errorCode, auditId, id.userId(), id.workspaceId(),
                id.grantId(), clientIdHash(id.clientId()), targets.size(), safeTargets.isEmpty() ? "-" : safeTargets};
        if (success) ACTIVITY.info(template, args); else ACTIVITY.warn(template, args);
    }

    /** client_id는 URL이라 본문 정화에서 지워진다. 같은 클라이언트를 묶어 볼 수 있게 SHA-256 앞 16자리만 남긴다. */
    static String clientIdHash(String clientId) {
        if (clientId == null) return "-";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(clientId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    record Event(@JsonProperty("audit_id") String auditId, @JsonProperty("occurred_at") Instant occurredAt,
                 @JsonProperty("user_id") String userId, @JsonProperty("workspace_id") String workspaceId,
                 @JsonProperty("client_id") String clientId, @JsonProperty("grant_id") String grantId,
                 @JsonProperty("tool_name") String toolName, @JsonProperty("target_ids") List<String> targetIds,
                 String result, @JsonProperty("error_code") String errorCode, String origin) {}
}
