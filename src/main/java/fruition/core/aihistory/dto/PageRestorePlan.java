package fruition.core.aihistory.dto;

import fruition.core.aihistory.domain.RestoreAction;

import java.util.List;

/**
 * 페이지 하나에 대한 복구 계획.
 *
 * @param targetRevision     {@link RestoreAction#restore}일 때 되돌릴 revision. 그 외에는 null
 * @param targetOperationId  그 revision을 만든 작업. llmPipeline이 조립 지시서에서 스냅샷 경로를
 *                           만들 때 쓴다. {@link RestoreAction#restore}가 아니면 null
 * @param contributionCount  복구 후 남는 기여 수. {@link RestoreAction#rebuild}의 목표값이며 삭제면 0
 * @param keepContributions  {@link RestoreAction#rebuild}일 때 조립할 조각을 적용 순서대로
 */
public record PageRestorePlan(
        String pageId,
        RestoreAction action,
        Long targetRevision,
        String targetOperationId,
        int contributionCount,
        List<Kept> keepContributions
) {

    /** 재조립에 살려둘 기여 하나. 순서가 결과를 바꾸므로 목록 순서를 그대로 지시서에 싣는다. */
    public record Kept(String operationId, String documentId, String objectKey) {}

    public static PageRestorePlan delete(String pageId) {
        return new PageRestorePlan(pageId, RestoreAction.delete, null, null, 0, List.of());
    }

    public static PageRestorePlan restore(String pageId, long targetRevision,
                                          String targetOperationId, int contributionCount) {
        return new PageRestorePlan(pageId, RestoreAction.restore, targetRevision,
                targetOperationId, contributionCount, List.of());
    }

    public static PageRestorePlan rebuild(String pageId, List<Kept> keepContributions) {
        return new PageRestorePlan(pageId, RestoreAction.rebuild, null, null,
                keepContributions.size(), List.copyOf(keepContributions));
    }
}
