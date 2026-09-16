package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record DocumentUploadResponse(
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @Schema(description = "업로드된 원본 파일명", example = "설계문서.pdf")
        String filename,

        @JsonProperty("mime_type")
        @Schema(description = "파일 MIME 타입. PDF와 Markdown만 허용되며 그 외는 415다.",
                example = "application/pdf")
        String mimeType,

        @JsonProperty("byte_size")
        @Schema(description = "파일 크기(바이트)", example = "482913")
        long byteSize,

        @Schema(description = "문서 처리 상태")
        DocumentStatus status,

        @JsonProperty("source_uri")
        @Schema(description = "원본 파일이 저장된 오브젝트 스토리지 경로")
        String sourceUri,

        @JsonProperty("uploaded_at")
        @Schema(description = "업로드 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant uploadedAt,

        @Schema(description = "본문 편집이 가능한지 여부. Markdown은 true, PDF는 false다.", example = "false")
        boolean editable,

        @JsonProperty("current_version")
        @Schema(description = "낙관적 잠금 버전. 이후 쓰기 요청의 base_version에 그대로 넣는다.", example = "1")
        long currentVersion,

        @JsonProperty("document_role")
        @Schema(description = "문서 역할. Markdown은 EDITABLE, PDF는 ORIGINAL이다.")
        DocumentRole documentRole
) {}
