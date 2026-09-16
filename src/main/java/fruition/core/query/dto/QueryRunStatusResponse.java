package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.domain.QueryRun;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "비동기 질의 상태. 완료 전에는 result가 없고, 실패하면 error가 채워진다.")
public record QueryRunStatusResponse(
        @JsonProperty("request_id")
        @Schema(description = "질의 run ID", example = "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String requestId,

        @Schema(description = "현재 상태", example = "completed")
        String status,

        @Schema(description = "이 질의를 처리한 LLM provider(실행 시점 snapshot)", example = "openai")
        String provider,

        @Schema(description = "이 질의를 처리한 모델명(실행 시점 snapshot)", example = "gpt-5-nano")
        String model,

        @JsonProperty("web_search_enabled")
        @Schema(description = "이 질의에 웹 검색이 허용됐는지(실행 시점 snapshot)", example = "false")
        boolean webSearchEnabled,

        @Schema(description = "완료된 질의의 결과. 완료 전에는 키가 빠진다.")
        QueryResponse result,

        @Schema(description = "실패 사유. 성공이면 키가 빠진다.")
        String error
) {
    public static QueryRunStatusResponse from(QueryRun run) {
        return new QueryRunStatusResponse(
                run.requestId(), run.status().name().toLowerCase(), run.provider(), run.model(), run.webSearchEnabled(),
                run.result(), run.errorMessage());
    }
}
