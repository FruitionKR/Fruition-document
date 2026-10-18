package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "현재 본문 이력 중 가장 최근 AI 적용")
public record DocumentAiChange(
        @Schema(description = "AI가 저장한 본문 버전", example = "3")
        long version,

        @Schema(description = "저장 경로", allowableValues = {"agent", "meeting_notes"}, example = "agent")
        String source,

        @JsonProperty("operation_id")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "GET /ai-operation-logs/{operation_id}로 여는 AI 작업 ID. 작업 로그가 없는 회의록 적용이면 null이다.",
                nullable = true, example = "op_7c2e4b1a9d3f4e6c8b0a2d5f7e9c1b3a")
        String operationId,

        @JsonProperty("changed_at")
        @Schema(description = "저장 시각(ISO-8601 UTC)", example = "2026-10-15T02:11:09Z")
        Instant changedAt
) {}
