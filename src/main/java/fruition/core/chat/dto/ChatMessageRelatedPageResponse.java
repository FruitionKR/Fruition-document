package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "답변과 관련된 Wiki 페이지")
public record ChatMessageRelatedPageResponse(
        @JsonProperty("wiki_page_id")
        @Schema(description = "Wiki 페이지 ID")
        String wikiPageId,

        @JsonProperty("page_type")
        @Schema(description = "페이지 종류", example = "Concept")
        String pageType,

        @Schema(description = "페이지 제목", example = "검색 인덱싱")
        String title,

        @Schema(description = "URL에 쓰는 식별자", example = "search-indexing")
        String slug,

        @JsonProperty("relevance_score")
        @Schema(description = "질문과의 관련도 점수", example = "0.87")
        double relevanceScore,

        @Schema(description = "이 페이지가 답변에서 맡은 역할")
        String role,

        @Schema(description = "그래프 탐색에서의 거리. 0이면 직접 매칭이다.", example = "1")
        int depth,

        @Schema(description = "관련도 순위(0부터)", example = "0")
        int rank
) {}
