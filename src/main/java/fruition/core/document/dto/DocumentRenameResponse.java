package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record DocumentRenameResponse(
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @Schema(description = "저장된 파일명", example = "회의록.md")
        String filename,

        @JsonProperty("display_name")
        @Schema(description = "변경된 표시 이름", example = "이름 바꾼 회의록")
        String displayName,

        @JsonProperty("current_version")
        @Schema(description = "변경 후 버전. 다음 쓰기의 base_version으로 쓴다.", example = "2")
        long currentVersion,

        @JsonProperty("updated_at")
        @Schema(description = "변경 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant updatedAt,

        @Schema(description = "실제로 이름이 바뀌었는지 여부. 같은 이름으로 요청하면 false다.", example = "true")
        boolean changed
) {}
