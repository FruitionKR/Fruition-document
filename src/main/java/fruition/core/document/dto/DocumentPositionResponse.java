package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record DocumentPositionResponse(
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @JsonProperty("folder_id")
        @Schema(description = "이동 후 폴더 ID. 루트면 null이다.")
        UUID folderId,

        @JsonProperty("sort_order")
        @Schema(description = "이동 후 정렬 순서", example = "1024")
        long sortOrder,

        @JsonProperty("current_version")
        @Schema(description = "이동 후 버전", example = "2")
        long currentVersion
) {
}
