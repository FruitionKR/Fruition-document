package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record FolderChildrenResponse(
        @Schema(description = "해당 폴더 바로 아래 항목 목록. 손자 항목은 포함하지 않는다.")
        List<Item> items) {

    // DocumentTreeResponse.Item과 단순 이름이 같아 명세에서 한쪽이 덮인다 — 스키마 이름을 명시한다.
    @Schema(name = "FolderChildItem", description = "폴더 바로 아래 항목. 트리와 달리 children을 담지 않는다.")
    public record Item(
            @Schema(description = "항목 종류", allowableValues = {"folder", "document"}, example = "document")
            String type,

            @Schema(description = "폴더면 UUID, 문서면 doc_ 접두 ID")
            String id,

            @Schema(description = "화면에 보여줄 이름", example = "회의록")
            String name,

            @JsonProperty("sort_order")
            @Schema(description = "같은 부모 안에서의 정렬 순서", example = "1024")
            long sortOrder,

            @JsonProperty("current_version")
            @Schema(description = "낙관적 잠금 버전. 이동·이름변경 요청의 base_version으로 쓴다.", example = "1")
            long currentVersion,

            @JsonProperty("has_children")
            @Schema(description = "하위 항목이 있는지 여부. 문서는 항상 false다.", example = "false")
            boolean hasChildren,

            // Agent 플래너가 "만든 날짜별로 정리" 같은 요청을 계획할 수 있게 문서에만 내려 준다(#137).
            @JsonProperty("created_at")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "문서가 처음 만들어지거나 업로드된 시각(ISO-8601 UTC). 폴더에는 없다.",
                    example = "2026-10-01T09:12:00Z", nullable = true)
            Instant createdAt,

            @JsonProperty("updated_at")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "문서를 마지막으로 바꾼 시각(ISO-8601 UTC). 폴더에는 없다.",
                    example = "2026-10-03T02:11:09Z", nullable = true)
            Instant updatedAt
    ) {
        public static Item folder(
                String id,
                String name,
                long sortOrder,
                long currentVersion,
                boolean hasChildren
        ) {
            return new Item("folder", id, name, sortOrder, currentVersion, hasChildren, null, null);
        }

        public static Item document(
                String id,
                String name,
                long sortOrder,
                long currentVersion,
                Instant createdAt,
                Instant updatedAt
        ) {
            return new Item("document", id, name, sortOrder, currentVersion, false, createdAt, updatedAt);
        }
    }
}
