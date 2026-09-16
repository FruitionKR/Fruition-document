package fruition.core.chat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 근거(evidence) 하나가 참조하는 문서·블록 쌍. 하나의 rank가 여러 문서의 block을 참조할 수 있어,
 * 대표 문서만 표현하는 legacy {@code source_document_id}/{@code source_block_ids}와 달리 전역 ref를 구조화한다.
 */
// PipelineQueryResponse.SourceRef와 단순 이름이 같아 명세에서 한 스키마로 합쳐진다.
// 지금은 필드가 같아 결과가 맞지만 한쪽만 바뀌면 조용히 다른 쪽 계약을 덮어쓰므로 이름을 분리한다.
@Schema(name = "ChatSourceRef",
        description = "근거가 가리키는 문서·block 쌍. 한 근거가 여러 문서를 참조할 수 있어 쌍으로 표현한다.")
public record SourceRef(
        @JsonProperty("source_document_id")
        @Schema(description = "근거가 있는 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String sourceDocumentId,

        @JsonProperty("source_block_id")
        @Schema(description = "문서 안에서 근거가 있는 block ID")
        String sourceBlockId
) {}
