package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** ai-svc가 계획이 승인 대기에 들어갔음을 알릴 때 보내는 run 소유자. */
public record AgentPlanReadyRequest(
        @NotBlank @JsonProperty("workspace_id") String workspaceId,
        @NotBlank @JsonProperty("user_id") String userId
) {}
