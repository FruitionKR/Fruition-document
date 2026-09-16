package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record DocumentBlocksResponse(
        @JsonProperty("document_id")
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @Schema(description = "원문에서 추출한 block 목록. Wiki 근거 링크가 이 block을 가리킨다.")
        List<DocumentBlockResponse> blocks
) {}
