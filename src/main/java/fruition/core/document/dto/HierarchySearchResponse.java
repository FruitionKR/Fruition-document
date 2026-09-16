package fruition.core.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record HierarchySearchResponse(
        @Schema(description = "이름 부분 일치로 찾은 폴더·문서 목록. 본문은 검색하지 않는다.")
        List<Match> results) {

    @Schema(name = "HierarchySearchMatch", description = "검색 결과 한 건")
    public record Match(
            @Schema(description = "항목 종류", allowableValues = {"folder", "document"}, example = "document")
            String type,

            @Schema(description = "폴더면 UUID, 문서면 doc_ 접두 ID")
            String id,

            @Schema(description = "화면에 보여줄 이름", example = "회의록")
            String name,

            @Schema(description = "루트부터 이 항목까지의 경로")
            List<BreadcrumbResponse.Node> breadcrumb
    ) {
    }
}
