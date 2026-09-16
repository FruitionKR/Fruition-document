package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "이전 버전 본문으로 되돌리는 요청. 되돌린 결과도 새 버전으로 쌓인다.")
public record DocumentContentRestoreRequest(
        @NotNull @Min(1) @JsonProperty("base_version")
        @Schema(description = "직전에 읽은 current_version. 서버 값과 다르면 409다.",
                minimum = "1", example = "4")
        Long baseVersion
) {
}
