package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "그래프의 Wiki 페이지 노드. 값이 없는 필드는 키 자체가 빠진다.")
public record WikiGraphNode(
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

        @Schema(description = "페이지 상태", example = "published")
        String status,

        @JsonProperty("source_document")
        @Schema(description = "이 페이지의 근거가 된 원본 문서")
        SourceDocRef sourceDocument
) {
    @Schema(description = "노드가 참조하는 원본 문서")
    public record SourceDocRef(
            @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String id,

            @Schema(description = "문서 파일명", example = "설계문서.pdf")
            String filename) {}
}
