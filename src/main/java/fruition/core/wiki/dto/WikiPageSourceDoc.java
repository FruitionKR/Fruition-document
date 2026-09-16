package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Wiki 페이지의 근거가 된 원본 문서")
public record WikiPageSourceDoc(
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @Schema(description = "문서 파일명", example = "설계문서.pdf")
        String filename,

        @JsonProperty("source_uri")
        @Schema(description = "원본 파일이 저장된 오브젝트 스토리지 경로")
        String sourceUri,

        @JsonProperty("relation_type")
        @Schema(description = "문서와 페이지의 관계 종류")
        String relationType,

        @Schema(description = "관계 신뢰도(0~1)", example = "0.87")
        double confidence
) {}
