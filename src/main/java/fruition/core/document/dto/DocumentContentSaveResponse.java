package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record DocumentContentSaveResponse(
        @JsonProperty("document_id")
        @Schema(description = "저장된 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @JsonProperty("current_version")
        @Schema(description = "저장 후 버전. 다음 저장의 base_version으로 쓴다.", example = "4")
        long currentVersion,

        @JsonProperty("content_hash")
        @Schema(description = "저장된 본문의 해시. 같은 내용을 다시 저장했는지 판별에 쓴다.")
        String contentHash,

        @JsonProperty("updated_at")
        @Schema(description = "저장 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt,

        @Schema(description = "실제로 본문이 바뀌었는지 여부. 같은 내용이면 false다.", example = "true")
        boolean changed,

        @Schema(description = "서버가 정규화한 뒤의 Markdown 본문")
        String markdown,

        @Schema(description = "본문과 함께 저장된 이미지 asset 목록")
        List<DocumentAttachmentSaveResponse> attachments
) {
    public DocumentContentSaveResponse(
            String documentId, long currentVersion, String contentHash, Instant updatedAt, boolean changed
    ) {
        this(documentId, currentVersion, contentHash, updatedAt, changed, null, List.of());
    }
}
