package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Concept 페이지에 걸린 링크. lint ADD_LINK·REMOVE_LINK가 source_ref·target_ref·relation을 쓴다.")
public record WikiPageLink(
        @JsonProperty("page_id")
        @Schema(description = "링크 반대쪽 Wiki 페이지 ID")
        String pageId,

        @Schema(description = "링크 반대쪽 페이지의 URL 식별자", example = "inverted-index")
        String slug,

        @Schema(description = "관계 종류", example = "concept_related_to")
        String relation,

        @Schema(description = "이 페이지 기준 방향", example = "outgoing")
        String direction,

        @JsonProperty("source_ref")
        @Schema(description = "링크 출발 참조")
        String sourceRef,

        @JsonProperty("target_ref")
        @Schema(description = "링크 도착 참조")
        String targetRef
) {}
