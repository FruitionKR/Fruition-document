package fruition.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;

public record McpProposalActor(
        @JsonProperty("invocation_id") String invocationId,
        @JsonProperty("grant_id") String grantId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("policy_generation") long policyGeneration,
        String origin,
        @JsonProperty("spec_version") String specVersion,
        @JsonProperty("tool_name") String toolName) {}
