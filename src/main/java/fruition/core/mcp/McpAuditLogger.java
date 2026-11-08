package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpAuditLogger {
    private static final Logger AUDIT = LoggerFactory.getLogger("fruition.mcp.audit");
    private final ObjectMapper mapper;
    public McpAuditLogger(ObjectMapper mapper) { this.mapper = mapper; }
    public void log(McpIdentity id, String tool, List<String> targets, String result, String errorCode) {
        try { AUDIT.info(mapper.writeValueAsString(new Event(UUID.randomUUID().toString(), Instant.now(),
                id.userId(), id.workspaceId(), id.clientId(), id.grantId(), tool, targets, result, errorCode,
                "MCP_CLIENT"))); }
        catch (Exception e) { AUDIT.error("MCP_AUDIT_SERIALIZATION_FAILED"); }
    }
    record Event(@JsonProperty("audit_id") String auditId, @JsonProperty("occurred_at") Instant occurredAt,
                 @JsonProperty("user_id") String userId, @JsonProperty("workspace_id") String workspaceId,
                 @JsonProperty("client_id") String clientId, @JsonProperty("grant_id") String grantId,
                 @JsonProperty("tool_name") String toolName, @JsonProperty("target_ids") List<String> targetIds,
                 String result, @JsonProperty("error_code") String errorCode, String origin) {}
}
