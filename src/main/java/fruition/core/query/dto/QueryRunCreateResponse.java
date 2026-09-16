package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.domain.QueryRun;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비동기 질의 접수 결과. request_id로 상태를 조회하거나 SSE를 구독한다.")
public record QueryRunCreateResponse(
        @JsonProperty("request_id")
        @Schema(description = "질의 run ID", example = "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String requestId,

        @Schema(description = "접수 직후 상태", example = "pending")
        String status
) {
    public static QueryRunCreateResponse from(QueryRun run) {
        return new QueryRunCreateResponse(run.requestId(), run.status().name().toLowerCase());
    }
}
