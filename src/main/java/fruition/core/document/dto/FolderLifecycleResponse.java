package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record FolderLifecycleResponse(
        @Schema(description = "폴더 ID", example = "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2")
        UUID id,

        @JsonProperty("current_version")
        @Schema(description = "변경 후 버전", example = "2")
        long currentVersion,

        @Schema(description = "휴지통에 있는지 여부. 복구하면 false다.", example = "true")
        boolean deleted,

        @JsonProperty("deleted_at")
        @Schema(description = "삭제 시각(ISO-8601 UTC). 복구된 경우 null이다.",
                example = "2026-08-13T04:25:24.371948Z")
        Instant deletedAt,

        @JsonProperty("delete_operation_id")
        @Schema(description = "삭제 작업 ID. 폴더와 함께 지워진 하위 항목을 한 번에 복구할 때 쓴다.")
        UUID deleteOperationId
) {
}
