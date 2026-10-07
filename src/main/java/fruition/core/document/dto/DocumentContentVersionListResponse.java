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
        @Schema(description = "현재 본문과 같은 최신 이력 번호. 미기록 편집 상태이면 null", example = "4", nullable = true)
        Long currentVersion,

        @JsonProperty("current_revision")
        @Schema(description = "현재 편집 revision. 저장 및 복원의 충돌 검사 기준")
        long currentRevision,

        @Schema(description = "저장 이력. 최신이 먼저 온다.")
        List<Item> versions
) {
    public DocumentContentVersionListResponse(String documentId, long currentVersion, List<Item> versions) {
        this(documentId, currentVersion, currentVersion, versions);
    }

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
            Instant createdAt,

            @JsonProperty("restored_from_version")
            @Schema(description = "버전 복원으로 만든 버전이면 복원 대상 버전 번호. 일반 저장·AI 적용·기록 이전 버전은 null",
                    example = "2", nullable = true)
            Long restoredFromVersion,

            @Schema(description = "이 본문의 내부 편집 revision. 화면 번호와 다를 수 있다")
            long revision,

            @JsonProperty("record_type")
            @Schema(description = "initial, manual, ai, before_ai, restore, before_restore, convert, legacy")
            String recordType
    ) {
        public Item(long version, String contentHash, String createdBy, Instant createdAt, Long restoredFromVersion) {
            this(version, contentHash, createdBy, createdAt, restoredFromVersion, version, "legacy");
        }
    }
}
