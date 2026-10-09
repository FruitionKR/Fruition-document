package fruition.core.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

public record DocumentPermissionRequest(
        @Pattern(regexp = "edit|view", message = "access는 edit, view 또는 null이어야 합니다.")
        @Schema(description = "edit(편집 가능) | view(보기만) | null(설정을 지워 상위 폴더 또는 기본값을 따름)",
                nullable = true, allowableValues = {"edit", "view"})
        String access
) {}
