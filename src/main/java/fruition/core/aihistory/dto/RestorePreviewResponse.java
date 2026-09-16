package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 복구 미리보기 응답. 되돌리기는 무를 수 없으므로 무엇이 삭제·복원·재작성되는지 먼저 보여준다.
 *
 * <p>Wiki 되돌리기면 {@code pages}가 차고, 문서 편집 되돌리기면 {@code document}가 찬다.
 * 둘이 동시에 차는 경우는 없다.
 *
 * @param previewToken 실행할 때 그대로 돌려줘야 한다. 그사이 대상이 바뀌면 409로 거절한다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "복구 미리보기. 되돌리기는 무를 수 없어 무엇이 바뀌는지 먼저 보여준다. "
        + "Wiki 복구면 pages가, 문서 편집 복구면 document가 찬다 — 둘이 동시에 차지는 않는다.")
public record RestorePreviewResponse(
        @JsonProperty("operation_id")
        @Schema(description = "되돌릴 대상 작업 ID", example = "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String operationId,

        @JsonProperty("delete_count")
        @Schema(description = "삭제될 Wiki 페이지 수", example = "1")
        int deleteCount,

        @JsonProperty("restore_count")
        @Schema(description = "이전 revision으로 되돌아갈 페이지 수", example = "2")
        int restoreCount,

        @JsonProperty("rebuild_count")
        @Schema(description = "남은 기여로 재작성될 페이지 수", example = "3")
        int rebuildCount,

        @Schema(description = "Wiki 복구일 때 페이지별 계획")
        List<Page> pages,

        @Schema(description = "문서 편집 복구일 때의 계획")
        DocumentRestorePlan document,

        @JsonProperty("preview_token")
        @Schema(description = "실행 요청에 그대로 넣어야 하는 토큰. 그사이 대상이 바뀌면 409다.")
        String previewToken
) {

    /**
     * @param action             delete · restore · rebuild
     * @param targetRevision     복원일 때 되돌릴 revision. 그 외에는 null
     * @param contributionCount  복구 후 남는 기여 수. 삭제면 0
     */
    @Schema(name = "RestorePreviewPage", description = "복구 대상 Wiki 페이지 하나의 계획")
    public record Page(
            @JsonProperty("page_id")
            @Schema(description = "Wiki 페이지 ID")
            String pageId,

            @Schema(description = "이 페이지에 할 일",
                    allowableValues = {"delete", "restore", "rebuild"}, example = "rebuild")
            String action,

            @JsonProperty("target_revision")
            @Schema(description = "restore일 때 되돌릴 revision. 그 외에는 null이다.", example = "4")
            Long targetRevision,

            @JsonProperty("contribution_count")
            @Schema(description = "복구 후 남는 기여 수. 삭제면 0이다.", example = "2")
            int contributionCount
    ) {}

    public static RestorePreviewResponse from(String operationId, RestorePlan plan, String previewToken) {
        List<Page> pages = plan.pages().stream()
                .map(p -> new Page(p.pageId(), p.action().name(), p.targetRevision(), p.contributionCount()))
                .toList();
        return new RestorePreviewResponse(operationId,
                plan.deleteCount(), plan.restoreCount(), plan.rebuildCount(), pages, null, previewToken);
    }

    /** 문서 편집 되돌리기. 페이지 목록이 비고 문서 계획만 담긴다. */
    public static RestorePreviewResponse from(String operationId, DocumentRestorePlan plan,
                                              String previewToken) {
        return new RestorePreviewResponse(operationId, 0, 1, 0, List.of(), plan, previewToken);
    }
}
