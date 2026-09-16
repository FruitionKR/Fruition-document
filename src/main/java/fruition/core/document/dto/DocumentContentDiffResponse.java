package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "두 버전 사이의 본문 차이. 줄 단위 hunk로 준다.")
public record DocumentContentDiffResponse(
        @JsonProperty("document_id")
        @Schema(description = "문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @JsonProperty("from_version")
        @Schema(description = "비교 기준 버전", example = "2")
        long fromVersion,

        @JsonProperty("to_version")
        @Schema(description = "비교 대상 버전", example = "3")
        long toVersion,

        @Schema(description = "추가된 줄 수", example = "12")
        int additions,

        @Schema(description = "삭제된 줄 수", example = "4")
        int deletions,

        @Schema(description = "변경 구간 목록")
        List<Hunk> hunks
) {
    @Schema(description = "연속된 변경 구간 하나")
    public record Hunk(
            @JsonProperty("old_start")
            @Schema(description = "이전 본문에서의 시작 줄 번호(1-based)", example = "10")
            int oldStart,

            @JsonProperty("old_lines")
            @Schema(description = "이전 본문에서 이 구간이 차지하는 줄 수", example = "3")
            int oldLines,

            @JsonProperty("new_start")
            @Schema(description = "새 본문에서의 시작 줄 번호(1-based)", example = "10")
            int newStart,

            @JsonProperty("new_lines")
            @Schema(description = "새 본문에서 이 구간이 차지하는 줄 수", example = "5")
            int newLines,

            @Schema(description = "구간을 이루는 줄 목록")
            List<Line> lines
    ) {}

    @Schema(description = "diff의 한 줄")
    public record Line(
            @Schema(description = "줄의 성격")
            Type type,

            @JsonProperty("old_line")
            @Schema(description = "이전 본문에서의 줄 번호. 추가된 줄이면 null이다.", example = "10")
            Integer oldLine,

            @JsonProperty("new_line")
            @Schema(description = "새 본문에서의 줄 번호. 삭제된 줄이면 null이다.", example = "10")
            Integer newLine,

            @Schema(description = "줄 내용")
            String content
    ) {}

    @Schema(description = "CONTEXT는 양쪽에 있는 줄, DELETE는 없어진 줄, ADD는 새로 생긴 줄이다.")
    public enum Type {
        CONTEXT,
        DELETE,
        ADD
    }
}
