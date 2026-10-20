package fruition.core.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** max_documents는 1..50 밖이면 서비스가 그 범위로 맞춘다. */
public record SkillFolderSourceReadRequest(
        @JsonProperty("workspace_id") @NotBlank String workspaceId,
        @JsonProperty("user_id") @NotBlank String userId,
        @JsonProperty("folder_id") @NotBlank String folderId,
        @JsonProperty("max_documents") @NotNull Integer maxDocuments
) {}
