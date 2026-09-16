package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record MarkdownDocumentCreateRequest(
        @JsonProperty("display_name")
        @Schema(description = "문서 표시 이름", example = "회의록")
        String displayName,

        @Schema(description = "문서 전체 Markdown 본문. 5MB를 넘으면 413이다.",
                example = "# 회의록\n\n- 첫 번째 안건")
        String markdown,

        @JsonProperty("folder_id")
        @Schema(description = "생성 위치 폴더 ID. 생략하면 루트에 만든다.",
                example = "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2")
        UUID folderId
) {
}
