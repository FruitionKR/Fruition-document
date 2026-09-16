package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Wiki 페이지 사이의 방향 있는 관계")
public record WikiGraphEdge(
        @JsonProperty("from_page_id")
        @Schema(description = "시작 페이지 ID")
        String fromPageId,

        @JsonProperty("to_page_id")
        @Schema(description = "도착 페이지 ID")
        String toPageId,

        @JsonProperty("link_type")
        @Schema(description = "관계 종류", example = "related")
        String linkType,

        @Schema(description = "관계를 설명하는 짧은 문구")
        String label,

        @Schema(description = "관계 신뢰도(0~1)", example = "0.87")
        double confidence
) {}
