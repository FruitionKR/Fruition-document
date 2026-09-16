package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 편집 잠금 상태. GET /documents/{id}의 edit_lock 필드와 잠금 API 응답에 공용으로 쓴다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "편집 잠금 상태. 잠금이 없으면 필드 자체가 빠진다.")
public record EditLockResponse(
        @JsonProperty("holder_user_id")
        @Schema(description = "현재 편집 중인 사용자 ID", example = "user_3f1c8a6b52d7411e9c04ab5d2e7f6081")
        String holderUserId,

        @JsonProperty("holder_display_name")
        @Schema(description = "현재 편집 중인 사용자의 표시 이름", example = "표시 이름")
        String holderDisplayName,

        @JsonProperty("expires_at")
        @Schema(description = "잠금 만료 시각(ISO-8601 UTC). 이 시각이 지나면 다른 사용자가 잠글 수 있다.",
                example = "2026-08-13T04:25:24.371948Z")
        Instant expiresAt
) {}
