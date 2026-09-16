package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "문서 삭제·복구 요청. 낙관적 잠금으로 동시 변경을 막는다.")
public record DocumentLifecycleRequest(
        @NotNull @JsonProperty("base_version")
        @Schema(description = "직전에 읽은 current_version. 서버 값과 다르면 409 DOCUMENT_VERSION_CONFLICT다.",
                example = "1")
        Long baseVersion
) {
}
