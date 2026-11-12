package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Wiki 페이지 상세. 값이 없는 필드는 키 자체가 빠진다.")
public record WikiPageDetailResponse(
        @Schema(description = "Wiki 페이지 ID")
        String id,

        @JsonProperty("page_type")
        @Schema(description = "페이지 종류", example = "Concept")
        String pageType,

        @Schema(description = "페이지 제목", example = "검색 인덱싱")
        String title,

        @Schema(description = "URL에 쓰는 식별자", example = "search-indexing")
        String slug,

        @Schema(description = "페이지 요약")
        String summary,

        @JsonProperty("markdown_uri")
        @Schema(description = "본문이 저장된 오브젝트 스토리지 경로")
        String markdownUri,

        @Schema(description = "페이지 본문 Markdown")
        String markdown,

        @Schema(description = "페이지 상태", example = "published")
        String status,

        @JsonProperty("created_at")
        @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @Schema(description = "현재 본문 revision. 버전 이력이 아직 없으면 키가 빠진다.", example = "3")
        Long revision,

        @JsonProperty("updated_at")
        @Schema(description = "마지막 변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt,

        @JsonProperty("source_documents")
        @Schema(description = "이 페이지의 근거가 된 원본 문서 목록")
        List<WikiPageSourceDoc> sourceDocuments,

        @JsonProperty("related_pages")
        @Schema(description = "그래프에서 이어진 관련 페이지 목록")
        List<WikiRelatedPage> relatedPages,

        @Schema(description = "Concept 페이지의 statement 목록. 다른 페이지 종류는 비어 있거나 키가 빠진다.")
        List<WikiPageStatement> statements,

        @Schema(description = "Concept 페이지에 걸린 링크 목록. 다른 페이지 종류는 비어 있거나 키가 빠진다.")
        List<WikiPageLink> links,

        @JsonProperty("lint_target_page_ids")
        @Schema(description = "이 페이지를 lint할 때 target_snapshot에 함께 넣어야 하는 페이지 ID 목록(Concept만)")
        List<String> lintTargetPageIds,

        @JsonProperty("lint_target_truncated")
        @Schema(description = "lint_target_page_ids가 상한에서 잘렸으면 true")
        Boolean lintTargetTruncated
) {}
