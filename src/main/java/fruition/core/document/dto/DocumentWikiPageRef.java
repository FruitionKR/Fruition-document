package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "이 문서에서 생성된 Wiki 페이지 참조")
public record DocumentWikiPageRef(
        @Schema(description = "Wiki 페이지 ID")
        String id,

        @JsonProperty("page_type")
        @Schema(description = "페이지 종류", example = "Concept")
        String pageType,

        @Schema(description = "페이지 제목", example = "검색 인덱싱")
        String title,

        @Schema(description = "URL에 쓰는 식별자", example = "search-indexing")
        String slug,

        @JsonProperty("relation_type")
        @Schema(description = "문서와 페이지의 관계 종류")
        String relationType,

        @Schema(description = "관계 신뢰도(0~1)", example = "0.87")
        Double confidence
) {}
