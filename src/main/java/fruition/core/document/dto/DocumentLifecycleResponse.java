package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record DocumentLifecycleResponse(
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @JsonProperty("current_version")
        @Schema(description = "변경 후 버전", example = "2")
        long currentVersion,

        @Schema(description = "휴지통에 있는지 여부. 복구하면 false다.", example = "true")
        boolean deleted,

        @JsonProperty("deleted_at")
        @Schema(description = "삭제 시각(ISO-8601 UTC). 복구된 경우 null이다.",
                example = "2026-08-13T04:25:24.371948Z")
        Instant deletedAt,

        @JsonProperty("sort_order")
        @Schema(description = "같은 폴더 안에서의 정렬 순서", example = "1024")
        long sortOrder
) {
}
