package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WikiPageRenameResponse(
        @Schema(description = "Wiki 페이지 ID")
        String id,

        @JsonProperty("page_type")
        @Schema(description = "페이지 종류", example = "Concept")
        String pageType,

        @Schema(description = "변경된 제목", example = "검색 인덱싱")
        String title,

        @JsonProperty("previous_title")
        @Schema(description = "변경 전 제목", example = "인덱싱")
        String previousTitle,

        @Schema(description = "변경 후 slug", example = "search-indexing")
        String slug,

        @JsonProperty("previous_slug")
        @Schema(description = "변경 전 slug", example = "indexing")
        String previousSlug,

        @JsonProperty("slug_updated")
        @Schema(description = "slug가 실제로 바뀌었는지 여부", example = "false")
        boolean slugUpdated,

        @JsonProperty("updated_at")
        @Schema(description = "변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt
) {}
