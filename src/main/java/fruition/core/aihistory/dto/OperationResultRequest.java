package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * llmPipeline이 ingest를 마치고 보내는 결과. 본문은 싣지 않고 어디에 썼는지만 알려준다.
 *
 * <p>{@code workspaceId}·{@code userId}·{@code targetDocumentId}는 권한 근거로 쓰지 않는다.
 * 요청 등록 때 저장한 값과 일치하는지만 확인한다. 콜백이 보낸 값을 믿으면 위조된 요청이
 * 다른 워크스페이스를 건드릴 수 있다.
 */
public record OperationResultRequest(
        @JsonProperty("operation_id") @NotBlank String operationId,
        @JsonProperty("operation_type") String operationType,
        @NotBlank String status,
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("target_document_id") String targetDocumentId,
        String summary,
        @JsonProperty("changed_pages") @NotNull List<ChangedPage> changedPages,
        @JsonProperty("failed_pages") List<FailedPage> failedPages,
        @JsonProperty("deleted_pages") List<String> deletedPages,
        @JsonProperty("link_changes") LinkChanges linkChanges,
        @JsonProperty("failed_actions") List<FailedAction> failedActions
) {

    /** 기존 ingest·lint 결과 생성 코드를 위한 호환 생성자다. */
    public OperationResultRequest(String operationId, String operationType, String status,
                                  String workspaceId, String userId, String targetDocumentId,
                                  String summary, List<ChangedPage> changedPages,
                                  List<FailedPage> failedPages) {
        this(operationId, operationType, status, workspaceId, userId, targetDocumentId,
                summary, changedPages, failedPages, null, null, null);
    }

    /** 재조립에만 쓴다. 실패한 페이지는 본문을 건드리지 않고 사유만 기록한다. */
    public record FailedPage(
            @JsonProperty("page_id") @NotBlank String pageId,
            String reason
    ) {}

    /** 재조립 결과에만 실린다. 없으면 전량 성공이다. */
    public List<FailedPage> failedPagesOrEmpty() {
        return failedPages == null ? List.of() : failedPages;
    }

    public List<String> deletedPagesOrEmpty() {
        return deletedPages == null ? List.of() : deletedPages;
    }

    public LinkChanges linkChangesOrEmpty() {
        return linkChanges == null ? new LinkChanges(List.of(), List.of()) : linkChanges;
    }

    public List<FailedAction> failedActionsOrEmpty() {
        return failedActions == null ? List.of() : failedActions;
    }

    public record LinkChanges(
            @JsonProperty("removed_links") List<Link> removedLinks,
            @JsonProperty("restored_links") List<Link> restoredLinks
    ) {
        public LinkChanges {
            removedLinks = removedLinks == null ? List.of() : removedLinks;
            restoredLinks = restoredLinks == null ? List.of() : restoredLinks;
        }
    }

    public record Link(String source, String target, String relation) {}

    public record FailedAction(
            String action,
            @JsonProperty("resource_id") String resourceId,
            String reason
    ) {}

    /**
     * @param markdownKey        그 작업이 쓴 본문 object key
     * @param contentHash        전송·저장 무결성 확인용
     * @param contributionStored 기여 조각을 남겼는지. 없으면 나중에 재조립할 수 없다
     */
    public record ChangedPage(
            @JsonProperty("page_id") @NotBlank String pageId,
            @JsonProperty("page_type") String pageType,
            @JsonProperty("markdown_key") @NotBlank String markdownKey,
            @JsonProperty("contribution_key") String contributionKey,
            @JsonProperty("content_hash") @NotBlank String contentHash,
            @JsonProperty("contribution_stored") Boolean contributionStored
    ) {}

    /** 부분 실패도 이미 만든 페이지는 담아 보내야 한다. 안 그러면 Wiki에만 있고 로그에 없는 페이지가 남는다. */
    public boolean isFailure() {
        return "failed".equals(status)
                || "partially_succeeded".equals(status)
                || !failedPagesOrEmpty().isEmpty()
                || !failedActionsOrEmpty().isEmpty();
    }
}
