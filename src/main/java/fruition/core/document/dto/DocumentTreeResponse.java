package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record DocumentTreeResponse(
        @Schema(description = "루트부터 재귀적으로 펼친 폴더·문서 트리")
        List<Item> items) {

    // FolderChildrenResponse.Item과 단순 이름이 같아 명세에서 한쪽이 덮인다 — 스키마 이름을 명시한다.
    @Schema(name = "DocumentTreeItem", description = "트리 항목. 폴더면 children이 있고 문서면 빠진다.")
    public record Item(
            @Schema(description = "항목 종류", allowableValues = {"folder", "document"}, example = "folder")
            String type,

            @Schema(description = "폴더면 UUID, 문서면 doc_ 접두 ID")
            String id,

            @Schema(description = "트리에 보여줄 이름. 폴더는 폴더 이름, 문서는 확장자를 포함한 파일명이다. "
                    + "문서가 사람에게 보이는 제목은 document.display_name에 있다.",
                    example = "설계")
            String name,

            @JsonProperty("sort_order")
            @Schema(description = "같은 부모 안에서의 정렬 순서", example = "1024")
            long sortOrder,

            @JsonProperty("current_version")
            @Schema(description = "낙관적 잠금 버전. 이동·이름변경 요청의 base_version으로 쓴다.", example = "1")
            long currentVersion,

            @JsonProperty("has_children")
            @Schema(description = "하위 항목이 있는지 여부", example = "true")
            boolean hasChildren,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "하위 항목. 문서 항목에는 키 자체가 없다.")
            List<Item> children,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "문서 메타데이터. 목록 조회가 주는 것과 같은 항목이다. "
                    + "폴더 항목에는 키 자체가 없다.")
            DocumentListResponse.DocumentItem document
    ) {
        public static Item folder(
                String id,
                String name,
                long sortOrder,
                long currentVersion,
                List<Item> children
        ) {
            return new Item("folder", id, name, sortOrder, currentVersion, !children.isEmpty(), children, null);
        }

        public static Item document(String id, String name, long sortOrder, long currentVersion,
                                    DocumentListResponse.DocumentItem document) {
            return new Item("document", id, name, sortOrder, currentVersion, false, null, document);
        }
    }
}
