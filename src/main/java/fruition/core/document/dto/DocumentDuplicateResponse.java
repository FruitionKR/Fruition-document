package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "복제로 새로 만들어진 문서")
public record DocumentDuplicateResponse(
        @Schema(description = "새 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @Schema(description = "저장된 파일명", example = "설계문서 (사본).pdf")
        String filename,

        @JsonProperty("display_name")
        @Schema(description = "화면에 보여줄 이름", example = "설계문서 (사본)")
        String displayName,

        @JsonProperty("mime_type")
        @Schema(description = "파일 MIME 타입", example = "application/pdf")
        String mimeType,

        @JsonProperty("byte_size")
        @Schema(description = "파일 크기(바이트)", example = "482913")
        long byteSize,

        @JsonProperty("current_version")
        @Schema(description = "새 문서의 시작 버전", example = "1")
        long currentVersion,

        @JsonProperty("folder_id")
        @Schema(description = "복제본이 놓인 폴더 ID. 루트면 null이다.")
        UUID folderId,

        @JsonProperty("source_document_id")
        @Schema(description = "복제 원본 문서 ID", example = "doc_8d4f1e6c3b0a97d25e4f831b9f4c7e2a")
        String sourceDocumentId,

        @JsonProperty("sort_order")
        @Schema(description = "같은 폴더 안에서의 정렬 순서", example = "2048")
        long sortOrder
) {
}
