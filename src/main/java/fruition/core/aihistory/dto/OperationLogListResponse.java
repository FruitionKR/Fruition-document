package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.aihistory.domain.OperationLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * AI 작업 로그 목록. {@code ai_operation_logs}만 읽으며 diff를 계산하지 않는다.
 *
 * @param nextCursor 다음 페이지 기준. null이면 마지막이다
 */
@Schema(description = "AI 작업 로그 목록. 변경분(diff)은 담기지 않으므로 상세 조회로 본다.")
public record OperationLogListResponse(
        @Schema(description = "작업 로그 목록. 최신이 먼저 온다.")
        List<Item> logs,

        @JsonProperty("next_cursor")
        @Schema(description = "다음 페이지를 받을 커서. null이면 마지막 페이지다.")
        String nextCursor
) {

    // 다른 응답의 중첩 Item과 단순 이름이 겹쳐 명세에서 덮인다 — 스키마 이름을 명시한다.
    @Schema(name = "OperationLogItem", description = "AI 작업 로그 한 건")
    public record Item(
            @JsonProperty("operation_id")
            @Schema(description = "작업 ID. 상세 조회·복구에 쓴다.",
                    example = "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String operationId,

            @JsonProperty("operation_type")
            @Schema(description = "작업 종류", example = "ingest")
            String operationType,

            @Schema(description = "작업 상태", example = "succeeded")
            String status,

            @JsonProperty("target_document_id")
            @Schema(description = "이 작업이 대상으로 삼은 문서 ID",
                    example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String targetDocumentId,

            @JsonProperty("target_display_name")
            @Schema(description = "작업 시작 시점의 대상 표시 이름")
            String targetDisplayName,

            @Schema(description = "작업 요약")
            String summary,

            @JsonProperty("changed_resource_count")
            @Schema(description = "이 작업이 바꾼 리소스 수", example = "3")
            int changedResourceCount,

            @JsonProperty("restored_from")
            @Schema(description = "복구 작업이라면 되돌린 원래 작업 ID")
            String restoredFrom,

            @JsonProperty("created_at")
            @Schema(description = "작업 시작 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant createdAt,

            @JsonProperty("completed_at")
            @Schema(description = "작업 완료 시각(ISO-8601 UTC). 진행 중이면 null이다.")
            Instant completedAt
    ) {
        public static Item from(OperationLog log) {
            return new Item(
                    log.getOperationId(),
                    log.getOperationType().name(),
                    log.getStatus().name(),
                    log.getTargetDocumentId(),
                    log.getTargetDisplayName(),
                    log.getSummary(),
                    log.getChangedResourceCount(),
                    log.getRestoredFrom(),
                    log.getCreatedAt(),
                    log.getCompletedAt());
        }
    }
}
