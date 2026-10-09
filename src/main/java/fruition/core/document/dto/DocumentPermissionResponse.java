package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public record DocumentPermissionResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "저장된 설정. null이면 직접 건 설정이 없다.", nullable = true, allowableValues = {"edit", "view"})
        String access
) {}
