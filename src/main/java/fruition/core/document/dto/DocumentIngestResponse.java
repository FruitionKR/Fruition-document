package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "ingest 요청 접수 결과. 실제 처리는 비동기로 진행되며 run_id로 상태를 추적한다.")
public record DocumentIngestResponse(
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String id,

        @JsonProperty("run_id")
        @Schema(description = "이번 ingest를 처리하는 pipeline run ID")
        String runId,

        @Schema(description = "접수 직후의 문서 상태")
        DocumentStatus status
) {
}
