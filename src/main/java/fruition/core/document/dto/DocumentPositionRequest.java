package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "문서 이동·순서 변경 요청")
public record DocumentPositionRequest(
        @JsonProperty("folder_id")
        @Schema(description = "옮길 폴더 ID. null이면 루트로 옮긴다.",
                example = "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2")
        UUID folderId,

        @JsonProperty("position")
        @Schema(description = "새 폴더 안에서의 0-based 삽입 위치. 생략하면 맨 뒤에 놓는다.", example = "0")
        Integer position,

        @NotNull @JsonProperty("base_version")
        @Schema(description = "직전에 읽은 current_version. 서버 값과 다르면 409다.", example = "1")
        Long baseVersion
) {
}
