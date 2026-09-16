package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FolderRenameRequest(
        @NotBlank
        @Schema(description = "새 폴더 이름", example = "설계 문서")
        String name,

        @NotNull @JsonProperty("base_version")
        @Schema(description = "직전에 읽은 current_version. 서버 값과 다르면 409다.", example = "1")
        Long baseVersion
) {
}
