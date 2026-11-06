package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "여러 PDF의 Markdown 변환 요청 결과. 문서마다 따로 처리해 일부만 실패할 수 있다.")
public record DocumentBatchConvertResponse(
        @Schema(description = "변환을 접수한 문서")
        List<Accepted> accepted,

        @Schema(description = "변환을 접수하지 못한 문서")
        List<Failed> failed
) {
    @Schema(name = "DocumentBatchConvertAccepted")
    public record Accepted(
            @JsonProperty("source_document_id")
            @Schema(description = "요청한 PDF 원본 문서 ID", example = "doc_abc12345")
            String sourceDocumentId,

            @Schema(description = "변환 결과를 담을 placeholder 문서")
            DocumentUploadResponse document
    ) {
    }

    @Schema(name = "DocumentBatchConvertFailed")
    public record Failed(
            @JsonProperty("document_id")
            @Schema(description = "요청한 문서 ID", example = "doc_abc12345")
            String documentId,

            @Schema(description = "단건 변환 API와 같은 오류 코드. 분류되지 않은 오류는 CONVERT_REQUEST_FAILED다.",
                    example = "INVALID_DOCUMENT_CONVERT_REQUEST")
            String code,

            @Schema(description = "사용자에게 그대로 보여도 되는 한국어 문구",
                    example = "PDF 원본 문서만 Markdown으로 변환할 수 있습니다.")
            String message
    ) {
    }
}
