package fruition.core.wikimaintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 위키 유지보수 상태: 마지막 lint 이후 위키가 변경됐으면 needs_lint = true. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Wiki 유지보수 상태. 값이 없는 필드는 키 자체가 빠진다.")
public record WikiMaintenanceStatusResponse(
        @JsonProperty("needs_lint")
        @Schema(description = "마지막 lint 이후 Wiki가 바뀌어 재점검이 필요한지 여부", example = "true")
        boolean needsLint,

        @JsonProperty("last_lint_at")
        @Schema(description = "마지막 lint 실행 시각(ISO-8601 UTC). 한 번도 안 돌렸으면 키가 빠진다.",
                example = "2026-08-13T04:25:24.371948Z")
        Instant lastLintAt,

        @JsonProperty("last_wiki_change_at")
        @Schema(description = "Wiki가 마지막으로 바뀐 시각(ISO-8601 UTC)",
                example = "2026-08-13T04:25:24.371948Z")
        Instant lastWikiChangeAt
) {}
