package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record DocumentListResponse(
        @Schema(description = "활성 문서 목록. 휴지통 문서는 포함되지 않는다.")
        List<DocumentItem> documents) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "값이 없는 필드는 키 자체가 빠진다. null 체크가 아니라 존재 여부로 확인한다.")
    public record DocumentItem(
            @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String id,

            @Schema(description = "저장된 파일명", example = "설계문서.pdf")
            String filename,

            @JsonProperty("mime_type")
            @Schema(description = "파일 MIME 타입", example = "application/pdf")
            String mimeType,

            @JsonProperty("byte_size")
            @Schema(description = "파일 크기(바이트)", example = "482913")
            long byteSize,

            @Schema(description = "문서 처리 상태")
            DocumentStatus status,

            @JsonProperty("source_uri")
            @Schema(description = "원본 파일이 저장된 오브젝트 스토리지 경로")
            String sourceUri,

            @JsonProperty("extracted_text_uri")
            @Schema(description = "추출된 본문 텍스트가 저장된 경로. 처리 전이면 없다.")
            String extractedTextUri,

            @JsonProperty("uploaded_at")
            @Schema(description = "업로드 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant uploadedAt,

            @JsonProperty("processed_at")
            @Schema(description = "처리 완료 시각(ISO-8601 UTC). 처리 전이면 없다.")
            Instant processedAt,

            @JsonProperty("processing_started_at")
            @Schema(description = "현재 처리 작업의 시작 시각(ISO-8601 UTC). 처리 시작 전이면 없다.")
            Instant processingStartedAt,

            @JsonProperty("error_message")
            @Schema(description = "status=failed일 때의 실패 사유")
            String errorMessage,

            @JsonProperty("pipeline_run_id")
            @Schema(description = "이 문서를 처리한 pipeline run ID")
            String pipelineRunId,

            @JsonProperty("processing_state")
            @Schema(description = "처리 진행 상태. status보다 세분화된 값이다.")
            DocumentProcessingState processingState,

            @JsonProperty("processing_stage")
            @Schema(description = "현재 처리 단계 이름", example = "ingest")
            String processingStage,

            @Schema(description = "문서가 속한 영역")
            String area,

            @JsonProperty("item_kind")
            @Schema(description = "트리에서의 항목 종류", example = "document")
            String itemKind,

            @JsonProperty("display_name")
            @Schema(description = "화면에 보여줄 이름", example = "설계문서")
            String displayName,

            @JsonProperty("file_type")
            @Schema(description = "확장자 기준 파일 종류", example = "pdf")
            String fileType,

            @JsonProperty("document_role")
            @Schema(description = "문서 역할. Markdown은 EDITABLE, PDF는 ORIGINAL이다.")
            DocumentRole documentRole,

            @Schema(description = "본문 편집이 가능한지 여부", example = "false")
            boolean editable,

            @JsonProperty("current_version")
            @Schema(description = "낙관적 잠금 버전. 이후 쓰기 요청의 base_version에 그대로 넣는다.", example = "1")
            long currentVersion,

            @JsonProperty("source_document_id")
            @Schema(description = "변환으로 만들어진 문서라면 원본 문서 ID")
            String sourceDocumentId,

            @JsonProperty("updated_at")
            @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant updatedAt,

            /** 마지막 ingest 이후 편집본이 바뀌어 재분석이 필요한지 (편집 가능 문서만 true 가능) */
            @JsonProperty("needs_reingest")
            @Schema(description = "마지막 ingest 이후 편집본이 바뀌어 재분석이 필요한지 여부. 편집 가능 문서만 true가 될 수 있다.",
                    example = "false")
            boolean needsReingest,

            @JsonProperty("folder_id")
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "부모 폴더 ID. 루트면 null이다.", nullable = true)
            UUID folderId,

            @JsonProperty("file_sha256")
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "같은 파일이 이미 있는지 업로드 전에 판별하는 SHA-256(hex). 원본(PDF)은 올린 파일 전체의 해시,"
                    + " 편집 문서는 현재 본문(UTF-8)의 해시다. 대용량 직접 업로드 직후처럼 아직 계산 전이면 null이다.",
                    nullable = true)
            String fileSha256,

            @JsonProperty("can_edit")
            @Schema(description = "현재 사용자가 본문 저장·이름 변경·이동을 할 수 있는지. 문서 소유자와 OWNER는 항상 true다.",
                    example = "true")
            boolean canEdit,

            @JsonProperty("can_delete")
            @Schema(description = "현재 사용자가 삭제·휴지통 복구를 할 수 있는지. 문서 소유자와 OWNER만 true다.", example = "false")
            boolean canDelete,

            @JsonProperty("permission")
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "이 문서에 직접 건 권한 설정(edit | view). null이면 상위 폴더 설정 또는 기본값(edit)을 따른다.",
                    nullable = true, allowableValues = {"edit", "view"})
            String permission,

            @JsonProperty("updated_by")
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "마지막으로 본문을 저장한 사용자 ID. 업로드 뒤 저장한 적이 없으면 null이다.", nullable = true)
            String updatedBy
    ) {}
}
