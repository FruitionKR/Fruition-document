package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "특정 버전의 본문 스냅샷")
public record DocumentContentVersionResponse(
        @JsonProperty("document_id")
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @Schema(description = "이 스냅샷의 버전 번호", example = "3")
        long version,

        @Schema(description = "그 시점의 전체 Markdown 본문")
        String markdown,

        @JsonProperty("content_hash")
        @Schema(description = "그 시점 본문의 해시")
        String contentHash,

        @JsonProperty("created_by")
        @Schema(description = "저장한 사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
        String createdBy,

        @JsonProperty("created_at")
        @Schema(description = "저장 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt
) {
}
