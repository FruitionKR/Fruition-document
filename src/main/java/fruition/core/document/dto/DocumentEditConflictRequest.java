package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "본문 저장이 409 DOCUMENT_VERSION_CONFLICT로 막혔을 때 내 본문을 충돌로 올려 보존하는 요청")
public record DocumentEditConflictRequest(
        @NotNull
        @Schema(description = "저장하려던 전체 Markdown 본문")
        String markdown,

        @NotNull @Min(1) @JsonProperty("base_revision")
        @Schema(description = "편집을 시작한 revision(저장 요청에 보냈던 base_revision)", minimum = "1", example = "4")
        Long baseRevision,

        @NotBlank @Size(max = 255) @JsonProperty("client_conflict_id")
        @Schema(description = "클라이언트가 만든 충돌 ID. 같은 값으로 다시 보내면 기존 충돌을 돌려준다.",
                example = "c7a1e0d2-5b9f-4c3e-8a6d-1f2e3d4c5b6a")
        String clientConflictId
) {
}
