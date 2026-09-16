package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "그래프에서 이어진 관련 Wiki 페이지")
public record WikiRelatedPage(
        @Schema(description = "Wiki 페이지 ID")
        String id,

        @JsonProperty("page_type")
        @Schema(description = "페이지 종류", example = "Concept")
        String pageType,

        @Schema(description = "페이지 제목", example = "역색인")
        String title,

        @Schema(description = "URL에 쓰는 식별자", example = "inverted-index")
        String slug,

        @JsonProperty("link_type")
        @Schema(description = "관계 종류", example = "related")
        String linkType,

        @Schema(description = "관계를 설명하는 짧은 문구")
        String label,

        @Schema(description = "관계 신뢰도(0~1)", example = "0.87")
        double confidence
) {}
