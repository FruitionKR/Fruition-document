package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "문서 상세. 값이 없는 필드는 키 자체가 빠지므로 존재 여부로 확인한다.")
public record DocumentDetailResponse(
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

        @JsonProperty("error_message")
        @Schema(description = "status=failed일 때의 실패 사유")
        String errorMessage,

        @JsonProperty("wiki_pages")
        @Schema(description = "이 문서에서 생성된 Wiki 페이지 참조 목록")
        List<DocumentWikiPageRef> wikiPages,

        @JsonProperty("pipeline_run_id")
        @Schema(description = "이 문서를 처리한 pipeline run ID")
        String pipelineRunId,

        @JsonProperty("processing_state")
        @Schema(description = "처리 진행 상태. status보다 세분화된 값이다.")
        DocumentProcessingState processingState,

        @JsonProperty("processing_stage")
        @Schema(description = "현재 처리 단계 이름", example = "ingest")
        String processingStage,

        @JsonProperty("display_name")
        @Schema(description = "화면에 보여줄 이름", example = "설계문서")
        String displayName,

        @JsonProperty("file_type")
        @Schema(description = "확장자 기준 파일 종류", example = "pdf")
        String fileType,

        @JsonProperty("document_role")
        @Schema(description = "문서 역할. Markdown은 EDITABLE, PDF는 ORIGINAL이다.")
        DocumentRole documentRole,

        @Schema(description = "본문 편집이 가능한지 여부", example = "true")
        boolean editable,

        @JsonProperty("current_version")
        @Schema(description = "낙관적 잠금 버전. 이후 쓰기 요청의 base_version에 그대로 넣는다.", example = "3")
        long currentVersion,

        @JsonProperty("edit_revision")
        @Schema(description = "본문 편집 회차. 내용 저장마다 증가한다.", example = "12")
        long editRevision,

        @JsonProperty("source_document_id")
        @Schema(description = "변환으로 만들어진 문서라면 원본 문서 ID")
        String sourceDocumentId,

        @JsonProperty("updated_at")
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt,

        @Schema(description = "편집 가능 문서의 전체 Markdown 본문")
        String markdown,

        @JsonProperty("edit_lock")
        @Schema(description = "다른 사용자가 편집 중이면 그 잠금 정보. 없으면 키가 빠진다.")
        EditLockResponse editLock
) {}
