package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "OWNER가 충돌을 해결하는 요청")
public record DocumentEditConflictResolveRequest(
        @NotNull
        @Pattern(regexp = "server|conflict|merged", message = "choice는 server, conflict, merged 중 하나여야 합니다.")
        @Schema(description = "server(서버 본 유지) | conflict(충돌 본 채택) | merged(직접 합친 본문)",
                allowableValues = {"server", "conflict", "merged"})
        String choice,

        @Schema(description = "합친 본문. choice가 merged일 때만 필수다.")
        String markdown,

        @Min(1) @JsonProperty("base_revision")
        @Schema(description = "목록에서 본 서버 현재 revision. conflict·merged일 때 필수이고, 서버 값과 다르면 409 DOCUMENT_VERSION_CONFLICT다.",
                minimum = "1", example = "5")
        Long baseRevision
) {
}
