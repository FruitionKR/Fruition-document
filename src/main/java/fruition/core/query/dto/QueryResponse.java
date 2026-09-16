package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.repository.PipelineQueryResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "질의 결과. 웹 검색 결과 URL과 인증 토큰은 포함되지 않는다.")
public record QueryResponse(
        @JsonProperty("user_message")
        @Schema(description = "저장된 질문 메시지")
        MessageSummary userMessage,

        @JsonProperty("assistant_message")
        @Schema(description = "저장된 답변 메시지")
        MessageSummary assistantMessage,

        @JsonProperty("related_pages")
        @Schema(description = "답변 근거가 된 Wiki 페이지 목록")
        List<PipelineQueryResponse.RelatedPage> relatedPages,

        @JsonProperty("evidence_snippets")
        @Schema(description = "답변을 뒷받침하는 원문 구절 목록")
        List<PipelineQueryResponse.EvidenceSnippet> evidenceSnippets,

        @JsonProperty("graph_context")
        @Schema(description = "답변에 쓰인 Wiki 그래프 문맥")
        PipelineQueryResponse.GraphContext graphContext,

        @JsonProperty("traversal_paths")
        @Schema(description = "그래프를 따라간 경로. 왜 이 페이지가 근거인지 설명한다.")
        List<PipelineQueryResponse.TraversalPath> traversalPaths,

        @JsonProperty("web_search_requested")
        @Schema(description = "요청에서 웹 검색을 허용했는지", example = "false")
        boolean webSearchRequested,

        @JsonProperty("web_search_executed")
        @Schema(description = "실제로 웹 검색이 수행됐는지. 허용해도 실패하면 false다.", example = "false")
        boolean webSearchExecuted,

        @JsonProperty("result_count")
        @Schema(description = "근거로 쓴 결과 수", example = "5")
        int resultCount,

        @JsonProperty("error_code")
        @Schema(description = "웹 검색이 실패했을 때의 사유",
                allowableValues = {"web_search_unavailable", "web_search_failed"})
        String errorCode
) {
    @Schema(description = "질의로 저장된 메시지 요약")
    public record MessageSummary(
            @Schema(description = "메시지 ID")
            String id,

            @Schema(description = "발화 주체", allowableValues = {"user", "assistant"}, example = "assistant")
            String role,

            @Schema(description = "메시지 본문")
            String content,

            @Schema(description = "처리 상태", example = "completed")
            String status,

            @JsonProperty("created_at")
            @Schema(description = "생성 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
            Instant createdAt
    ) {}
}
