package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record SkillReferenceReadRequest(
        @JsonProperty("workspace_id") @NotBlank String workspaceId,
        @JsonProperty("user_id") @NotBlank String userId,
        @JsonProperty("document_id") @NotBlank String documentId
) {}
