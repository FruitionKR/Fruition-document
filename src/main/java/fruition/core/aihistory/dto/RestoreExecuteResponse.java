package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 복구 실행 결과.
 *
 * @param operationId  이번 복구 작업 식별자. 진행 상황을 이 id로 조회한다
 * @param restoredFrom 되돌린 대상 작업
 * @param rebuilding   llmPipeline 결과를 기다리는 중인지. false면 통지가 실패해 보류된 것이다
 */
@Schema(description = "복구 실행 결과. Wiki 복구는 재작성이 끝나야 확정되므로 이 응답만으로 완료가 아니다.")
public record RestoreExecuteResponse(
        @JsonProperty("run_id")
        @Schema(description = "재작성을 처리하는 run ID. 문서 편집 되돌리기에는 없다.")
        String runId,

        @JsonProperty("operation_id")
        @Schema(description = "이번 복구 작업 ID. 진행 상황을 이 값으로 조회한다.",
                example = "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String operationId,

        @JsonProperty("restored_from")
        @Schema(description = "되돌린 대상 작업 ID", example = "op_8d4f1e6c3b0a97d25e4f831b9f4c7e2a")
        String restoredFrom,

        @JsonProperty("delete_count")
        @Schema(description = "삭제되는 Wiki 페이지 수", example = "1")
        int deleteCount,

        @JsonProperty("restore_count")
        @Schema(description = "이전 revision으로 되돌아가는 페이지 수", example = "2")
        int restoreCount,

        @JsonProperty("rebuild_count")
        @Schema(description = "남은 기여로 재작성되는 페이지 수", example = "3")
        int rebuildCount,

        @Schema(description = "재작성 결과를 기다리는 중인지 여부. false면 통지가 실패해 보류된 것이다.",
                example = "true")
        boolean rebuilding,

        @Schema(description = "복구 상태",
                allowableValues = {"succeeded", "rebuilding", "notify_pending", "queued"},
                example = "rebuilding")
        String status
) {
    /** 문서 편집 되돌리기. 재작성이 없어 이 시점에 이미 끝나 있다. */
    public static RestoreExecuteResponse forDocument(String operationId, String restoredFrom) {
        return new RestoreExecuteResponse(null, operationId, restoredFrom, 0, 1, 0, false, "succeeded");
    }

    public static RestoreExecuteResponse from(String operationId, String restoredFrom,
                                              RestorePlan plan, boolean notified) {
        // 재작성 대상이 없어도 llmPipeline이 링크·임베딩을 정리하고 결과를 보내온다.
        // 확정은 그 결과를 받을 때 하므로 여기서는 완료로 답하지 않는다.
        String status = notified ? "rebuilding" : "notify_pending";
        return new RestoreExecuteResponse(null, operationId, restoredFrom,
                plan.deleteCount(), plan.restoreCount(), plan.rebuildCount(),
                notified, status);
    }

    public static RestoreExecuteResponse queued(String runId, String operationId,
                                                String restoredFrom, RestorePlan plan) {
        return new RestoreExecuteResponse(runId, operationId, restoredFrom,
                plan.deleteCount(), plan.restoreCount(), plan.rebuildCount(), true, "queued");
    }
}
