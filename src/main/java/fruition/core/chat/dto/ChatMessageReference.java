package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.chat.domain.SourceRef;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "답변의 근거가 된 원문 참조. 값이 없는 필드는 키 자체가 빠진다.")
public record ChatMessageReference(
        @Schema(description = "참조 ID", example = "1")
        long id,

        @JsonProperty("reference_type")
        @Schema(description = "참조 종류")
        String referenceType,

        @Schema(description = "근거 순위(0부터). 낮을수록 강한 근거다.", example = "0")
        Integer rank,

        @JsonProperty("source_document_id")
        @Schema(description = "근거가 된 원본 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String sourceDocumentId,

        @JsonProperty("source_block_ids")
        @Schema(description = "근거가 된 원문 block ID 목록")
        List<String> sourceBlockIds,

        @Schema(description = "근거 구절 원문")
        String text,

        @JsonProperty("source_refs")
        @Schema(description = "문서·block 쌍으로 표현한 근거 위치 목록")
        List<SourceRef> sourceRefs
) {}
