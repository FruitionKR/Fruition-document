package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record DocumentRenameRequest(
        @JsonProperty("display_name")
        @Schema(description = "새 표시 이름", example = "이름 바꾼 회의록")
        String displayName,

        @NotNull(message = "base_version은 필수입니다.")
        @JsonProperty("base_version")
        @Schema(description = "직전에 읽은 current_version. 서버 값과 다르면 409 DOCUMENT_VERSION_CONFLICT다.",
                example = "1")
        Long baseVersion
) {}
