package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "첨부 문서 수정 계획의 문서별 변경 미리 보기. AI 응답에서 읽을 수 있는 문서만 남기고 제목은 현재 값으로 덮는다.")
public record AgentRunEditPreviewsResponse(
        @Schema(description = "문서별 미리 보기")
        List<Item> items,

        @JsonProperty("expires_at")
        @Schema(description = "편집안 만료 시각. 지나면 수락할 수 없다. 편집안이 없으면 null이다.",
                example = "2026-11-05T09:00:00Z", nullable = true)
        String expiresAt
) {
    public AgentRunEditPreviewsResponse {
        items = items == null ? List.of() : items;
    }

    @Schema(name = "AgentDocumentEditPreview", description = "문서 하나의 변경")
    public record Item(
            @JsonProperty("operation_id")
            @Schema(description = "계획 안의 작업 ID. 부분 승인의 operation_ids에 쓴다.")
            String operationId,

            @JsonProperty("document_id")
            @Schema(description = "수정할 문서 ID")
            String documentId,

            @JsonProperty("display_name")
            @Schema(description = "문서의 현재 제목")
            String displayName,

            @JsonProperty("base_version")
            @Schema(description = "편집안의 기준 편집 revision", example = "3")
            long baseVersion,

            @Schema(description = "변경 요약")
            String summary,

            @JsonProperty("added_lines")
            @Schema(description = "추가된 줄 수", example = "12")
            int addedLines,

            @JsonProperty("removed_lines")
            @Schema(description = "삭제된 줄 수", example = "4")
            int removedLines,

            @Schema(description = "변경 구간. 앞뒤 문맥 3줄을 포함한다.")
            List<Hunk> hunks
    ) {
        public Item withDisplayName(String currentDisplayName) {
            return new Item(operationId, documentId, currentDisplayName, baseVersion, summary,
                    addedLines, removedLines, hunks);
        }
    }

    @Schema(name = "AgentDocumentEditPreviewHunk", description = "연속된 변경 구간 하나")
    public record Hunk(
            @JsonProperty("before_start")
            @Schema(description = "수정 전 본문에서의 시작 줄 번호(1-based)", example = "10")
            int beforeStart,

            @JsonProperty("after_start")
            @Schema(description = "수정 후 본문에서의 시작 줄 번호(1-based)", example = "10")
            int afterStart,

            @Schema(description = "구간을 이루는 줄 목록")
            List<Line> lines
    ) {}

    @Schema(name = "AgentDocumentEditPreviewLine", description = "diff의 한 줄")
    public record Line(
            @Schema(description = "context는 양쪽에 같은 줄, delete는 지운 줄, insert는 새 줄", allowableValues = {"context", "delete", "insert"})
            String type,

            @Schema(description = "줄 내용")
            String text
    ) {}
}
