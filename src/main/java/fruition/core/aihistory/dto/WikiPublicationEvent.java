package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Body-free durable result emitted after an AI wiki publication commits. */
public record WikiPublicationEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("publication_id") String publicationId,
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("user_id") String userId,
        Kind kind,
        Outcome outcome,
        OperationResultRequest result,
        @JsonProperty("published_reference_hash") String publishedReferenceHash,
        @JsonProperty("committed_at") Instant committedAt
) {
    public static final String TYPE = "ai.wiki.publication.v1";

    public enum Kind { INGEST, MANUAL_EDIT, LINT, RESTORE, RECOVERY }
    public enum Outcome { PUBLISHED, NO_CHANGE }
}
