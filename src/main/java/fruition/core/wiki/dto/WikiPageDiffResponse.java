package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.MarkdownDiff;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Wiki 페이지 두 revision 사이의 변경분. hunk 구조는 문서 diff와 같다. */
@Schema(description = "Wiki 페이지 두 revision 사이의 변경분. hunk 구조는 문서 diff와 같다.")
public record WikiPageDiffResponse(
        @JsonProperty("page_id")
        @Schema(description = "Wiki 페이지 ID")
        String pageId,

        @JsonProperty("from_revision")
        @Schema(description = "비교 기준 revision", example = "2")
        long fromRevision,

        @JsonProperty("to_revision")
        @Schema(description = "비교 대상 revision", example = "3")
        long toRevision,

        @Schema(description = "추가된 줄 수", example = "12")
        int additions,

        @Schema(description = "삭제된 줄 수", example = "4")
        int deletions,

        @Schema(description = "변경 구간 목록")
        List<DocumentContentDiffResponse.Hunk> hunks
) {
    public static WikiPageDiffResponse from(String pageId, MarkdownDiff diff) {
        return new WikiPageDiffResponse(pageId, diff.fromVersion(), diff.toVersion(),
                diff.additions(), diff.deletions(), diff.hunks());
    }
}
