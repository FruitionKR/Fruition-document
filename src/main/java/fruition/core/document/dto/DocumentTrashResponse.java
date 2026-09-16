package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentTrashResponse(
        @Schema(description = "삭제되어 휴지통에 있는 문서 목록")
        List<DocumentTrashItem> documents) {

    public record DocumentTrashItem(
            @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String id,

            @Schema(description = "저장된 파일명", example = "설계문서.pdf")
            String filename,

            @JsonProperty("display_name")
            @Schema(description = "화면에 보여줄 이름", example = "설계문서")
            String displayName,

            @JsonProperty("document_role")
            @Schema(description = "문서 역할")
            DocumentRole documentRole,

            @JsonProperty("current_version")
            @Schema(description = "복구 요청의 base_version에 넣을 현재 버전", example = "3")
            long currentVersion,

            @JsonProperty("deleted_at")
            @Schema(description = "삭제 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant deletedAt,

            @JsonProperty("deleted_by")
            @Schema(description = "삭제한 사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
            String deletedBy,

            @JsonProperty("delete_operation_id")
            @Schema(description = "삭제를 수행한 작업 ID. 같은 작업으로 지워진 항목을 묶어 복구할 때 쓴다.")
            UUID deleteOperationId,

            @JsonProperty("source_document_id")
            @Schema(description = "변환으로 만들어진 문서라면 원본 문서 ID")
            String sourceDocumentId
    ) {
    }
}
