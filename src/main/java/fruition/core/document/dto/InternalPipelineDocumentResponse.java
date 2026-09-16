package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InternalPipelineDocumentResponse(
        String id,
        @JsonProperty("user_id") String userId,
        @JsonProperty("workspace_id") String workspaceId,
        String filename,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("source_uri") String sourceUri,
        @JsonProperty("extracted_text_uri") String extractedTextUri,
        @JsonProperty("source_revision") long sourceRevision,
        @JsonProperty("source_content_hash") String sourceContentHash
) {}
