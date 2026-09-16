package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentToolReadRequest(
        @NotBlank @JsonProperty("run_id") String runId,
        @NotBlank @JsonProperty("workspace_id") String workspaceId,
        @NotBlank @JsonProperty("user_id") String userId,
        @NotNull JsonNode arguments
) {}
