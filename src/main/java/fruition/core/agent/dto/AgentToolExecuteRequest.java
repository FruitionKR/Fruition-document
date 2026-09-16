package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AgentToolExecuteRequest(
        @NotBlank @JsonProperty("run_id") String runId,
        @NotBlank @JsonProperty("workspace_id") String workspaceId,
        @NotBlank @JsonProperty("user_id") String userId,
        @NotBlank @JsonProperty("plan_id") String planId,
        @Positive @JsonProperty("plan_version") int planVersion,
        @NotBlank @Size(min = 64, max = 64) @JsonProperty("operation_hash") String operationHash,
        @NotBlank @JsonProperty("operation_id") String operationId,
        @NotBlank @JsonProperty("idempotency_key") String idempotencyKey,
        @NotNull JsonNode arguments
) {}
