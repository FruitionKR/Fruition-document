package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentEditConflictResponse(
        @Schema(description = "충돌 ID")
        UUID id,

        @JsonProperty("document_id")
        @Schema(description = "문서 ID")
        String documentId,

        @JsonProperty("base_revision")
        @Schema(description = "충돌 본이 편집을 시작한 revision", example = "4")
        long baseRevision,

        @Schema(description = "충돌 본 Markdown")
        String markdown,

        @JsonProperty("author_user_id")
        @Schema(description = "충돌을 등록한 사용자")
        String authorUserId,

        @Schema(description = "open(미해결) | resolved(해결됨)", allowableValues = {"open", "resolved"})
        String status,

        @Schema(description = "고른 본문. 미해결이면 null", nullable = true, allowableValues = {"server", "conflict", "merged"})
        String resolution,

        @JsonProperty("resolved_by")
        @Schema(description = "해결한 OWNER. 미해결이면 null", nullable = true)
        String resolvedBy,

        @JsonProperty("resolved_revision")
        @Schema(description = "해결 후 문서 revision. server면 그 시점 서버 revision이다. 미해결이면 null", nullable = true)
        Long resolvedRevision,

        @JsonProperty("created_at")
        @Schema(description = "등록 시각")
        Instant createdAt,

        @JsonProperty("resolved_at")
        @Schema(description = "해결 시각. 미해결이면 null", nullable = true)
        Instant resolvedAt
) {
}
