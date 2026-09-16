package fruition.core.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record BreadcrumbResponse(
        @Schema(description = "루트에서 대상까지의 경로. 마지막 원소가 대상 자신이다.")
        List<Node> path) {

    @Schema(name = "BreadcrumbNode", description = "경로 상의 한 단계")
    public record Node(
            @Schema(description = "항목 종류", allowableValues = {"folder", "document"}, example = "folder")
            String type,

            @Schema(description = "폴더면 UUID, 문서면 doc_ 접두 ID")
            String id,

            @Schema(description = "화면에 보여줄 이름", example = "설계")
            String name) {

        public static Node folder(String id, String name) {
            return new Node("folder", id, name);
        }

        public static Node document(String id, String name) {
            return new Node("document", id, name);
        }
    }
}
