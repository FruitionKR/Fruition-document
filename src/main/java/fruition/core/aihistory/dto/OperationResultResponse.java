package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 콜백 수신 결과. 재조립 일부 실패는 {@code partially_succeeded}로 돌려주므로
 * llmPipeline이 자기 보고와 다르게 반영됐는지 알 수 있다.
 */
public record OperationResultResponse(
        @JsonProperty("operation_id") String operationId,
        String status,
        @JsonProperty("recorded_changes") int recordedChanges
) {}
