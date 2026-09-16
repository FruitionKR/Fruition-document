package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record DocumentContentVersionListResponse(
        @JsonProperty("document_id")
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @JsonProperty("current_version")
        @Schema(description = "현재 버전", example = "4")
        long currentVersion,

        @Schema(description = "저장 이력. 최신이 먼저 온다.")
        List<Item> versions
) {
    // 다른 응답의 중첩 Item과 단순 이름이 겹쳐 명세에서 덮인다 — 스키마 이름을 명시한다.
    @Schema(name = "DocumentContentVersionItem", description = "본문 저장 이력 한 건")
    public record Item(
            @Schema(description = "해당 시점의 버전 번호", example = "3")
            long version,

            @JsonProperty("content_hash")
            @Schema(description = "그 시점 본문의 해시")
            String contentHash,

            @JsonProperty("created_by")
            @Schema(description = "저장한 사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
            String createdBy,

            @JsonProperty("created_at")
            @Schema(description = "저장 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant createdAt
    ) {}
}
