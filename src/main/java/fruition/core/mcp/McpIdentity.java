package fruition.core.mcp;

import java.util.Set;

public record McpIdentity(String userId, String workspaceId, String clientId, String grantId,
                          long policyGeneration, Set<String> scopes, String role, Set<String> permissions,
                          String clientName) {
    public McpIdentity(String userId, String workspaceId, String clientId, String grantId,
                       long policyGeneration, Set<String> scopes, String role, Set<String> permissions) {
        this(userId, workspaceId, clientId, grantId, policyGeneration, scopes, role, permissions, null);
    }

    public boolean owner() { return "OWNER".equals(role); }
    public boolean canConvert() { return permissions.contains("document.convert"); }
}
