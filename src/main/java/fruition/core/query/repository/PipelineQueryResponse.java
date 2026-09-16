package fruition.core.query.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PipelineQueryResponse(
        @Schema(description = "질문에 대한 답변 본문")
        String answer,

        @JsonProperty("updated_conversation_summary")
        @Schema(description = "이번 턴까지 반영해 pipeline이 갱신한 누적 대화 요약. "
                + "대화가 짧으면 null이다.", nullable = true)
        String updatedConversationSummary,

        @JsonProperty("related_pages")
        @Schema(description = "답변 근거가 된 Wiki 페이지 목록")
        List<RelatedPage> relatedPages,

        @JsonProperty("evidence_snippets")
        @Schema(description = "답변을 뒷받침하는 원문 구절 목록")
        List<EvidenceSnippet> evidenceSnippets,

        @JsonProperty("graph_context")
        @Schema(description = "답변에 쓰인 Wiki 그래프 문맥")
        GraphContext graphContext,

        @JsonProperty("traversal_paths")
        @Schema(description = "그래프를 따라간 경로 목록")
        List<TraversalPath> traversalPaths,

        @JsonProperty("web_search_requested")
        @Schema(description = "요청에서 웹 검색을 허용했는지", example = "false")
        boolean webSearchRequested,

        @JsonProperty("web_search_executed")
        @Schema(description = "실제로 웹 검색이 수행됐는지", example = "false")
        boolean webSearchExecuted,

        @JsonProperty("result_count")
        @Schema(description = "근거로 쓴 결과 수", example = "5")
        int resultCount,

        @JsonProperty("error_code")
        @Schema(description = "웹 검색이 실패했을 때의 사유",
                allowableValues = {"web_search_unavailable", "web_search_failed"})
        String errorCode
) {
    @Schema(description = "답변 근거가 된 Wiki 페이지")
    public record RelatedPage(
            @Schema(description = "Wiki 페이지 ID")
            String id,

            @JsonProperty("page_type")
            @Schema(description = "페이지 종류", example = "Concept")
            String pageType,

            @Schema(description = "페이지 제목", example = "검색 인덱싱")
            String title,

            @Schema(description = "URL에 쓰는 식별자", example = "search-indexing")
            String slug,

            @JsonProperty("relevance_score")
            @Schema(description = "질문과의 관련도 점수", example = "0.87")
            double relevanceScore,

            @Schema(description = "이 페이지가 답변에서 맡은 역할")
            String role,

            @Schema(description = "그래프 탐색에서의 거리. 0이면 직접 매칭이다.", example = "1")
            int depth
    ) {}

    @Schema(description = "답변을 뒷받침하는 원문 구절")
    public record EvidenceSnippet(
            @Schema(description = "근거 순위(0부터). 낮을수록 강한 근거다.", example = "0")
            int rank,

            @JsonProperty("source_document_id")
            @Schema(description = "대표 근거 문서 ID. 여러 문서를 참조하면 source_refs를 본다.",
                    example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String sourceDocumentId,

            @JsonProperty("source_block_ids")
            @Schema(description = "대표 문서 안에서의 근거 block ID 목록")
            List<String> sourceBlockIds,

            @JsonProperty("source_refs")
            @Schema(description = "문서·block 쌍으로 표현한 전체 근거 위치. 여러 문서를 걸칠 수 있다.")
            List<SourceRef> sourceRefs,

            @Schema(description = "근거 구절 원문")
            String text
    ) {}

    // fruition.core.chat.domain.SourceRef와 이름이 같아 명세가 합쳐지는 것을 막는다.
    @Schema(name = "QuerySourceRef",
            description = "근거가 가리키는 문서·block 쌍. 한 근거가 여러 문서를 참조할 수 있어 쌍으로 표현한다.")
    public record SourceRef(
            @JsonProperty("source_document_id")
            @Schema(description = "근거가 있는 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
            String sourceDocumentId,

            @JsonProperty("source_block_id")
            @Schema(description = "문서 안에서 근거가 있는 block ID")
            String sourceBlockId
    ) {}

    @Schema(description = "답변에 쓰인 Wiki 그래프의 부분 그래프")
    public record GraphContext(
            @Schema(description = "문맥에 포함된 페이지 노드")
            List<RelatedPage> nodes,

            @Schema(description = "노드 사이의 관계")
            List<GraphEdge> edges
    ) {}

    @Schema(description = "질의 문맥에서의 페이지 간 관계")
    public record GraphEdge(
            @JsonProperty("from_page_id")
            @Schema(description = "시작 페이지 ID")
            String fromPageId,

            @JsonProperty("to_page_id")
            @Schema(description = "도착 페이지 ID")
            String toPageId,

            @JsonProperty("link_type")
            @Schema(description = "관계 종류", example = "related")
            String linkType,

            @Schema(description = "이 관계가 답변에서 맡은 역할")
            String role,

            @Schema(description = "관계 점수", example = "0.72")
            double score
    ) {}

    @Schema(description = "그래프를 따라간 한 경로. 왜 이 페이지가 근거인지 설명한다.")
    public record TraversalPath(
            @JsonProperty("path_id")
            @Schema(description = "경로 ID")
            String pathId,

            @Schema(description = "이 경로가 답변에서 맡은 역할")
            String role,

            @JsonProperty("used_for_answer")
            @Schema(description = "이 경로가 실제 답변 생성에 쓰였는지 여부", example = "true")
            boolean usedForAnswer,

            @Schema(description = "경로 점수", example = "0.72")
            double score,

            @JsonProperty("stop_reason")
            @Schema(description = "탐색을 멈춘 이유")
            String stopReason,

            @Schema(description = "경로가 지나간 페이지 ID 목록(순서대로)")
            List<String> nodes,

            @Schema(description = "경로를 이루는 관계 목록(순서대로)")
            List<GraphEdge> edges
    ) {}
}
