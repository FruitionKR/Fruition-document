package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.service.ChangeDiffLoader;
import fruition.core.document.dto.DocumentContentDiffResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 작업 상세. 그 작업이 바꾼 리소스를 함께 반환한다.
 *
 * <p>{@code additions}·{@code deletions}는 저장 시점에 계산해 둔 값이라 다시 세지 않는다.
 * ingest·lint는 생성·삭제된 Wiki 제목과 유형만 전달한다. 그 외 작업의 {@code hunks}는 조회 시점에 계산한다.
 */
@Schema(description = "AI 작업 상세. 그 작업이 바꾼 리소스를 함께 반환한다. "
        + "ingest·lint는 생성·삭제된 Wiki 제목과 유형만 전달하고 그 외 작업은 변경분을 포함한다.")
public record OperationLogDetailResponse(
        @JsonProperty("operation_id")
        @Schema(description = "작업 ID", example = "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String operationId,

        @JsonProperty("operation_type")
        @Schema(description = "작업 종류", example = "ingest")
        String operationType,

        @Schema(description = "작업 상태", example = "succeeded")
        String status,

        @JsonProperty("target_document_id")
        @Schema(description = "이 작업이 대상으로 삼은 문서 ID",
                example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String targetDocumentId,

        @JsonProperty("target_display_name")
        @Schema(description = "작업 시작 시점의 대상 표시 이름")
        String targetDisplayName,

        @Schema(description = "작업 요약")
        String summary,

        @JsonProperty("changed_resource_count")
        @Schema(description = "이 작업이 바꾼 리소스 수", example = "3")
        int changedResourceCount,

        @JsonProperty("restored_from")
        @Schema(description = "복구 작업이라면 되돌린 원래 작업 ID")
        String restoredFrom,

        @JsonProperty("created_at")
        @Schema(description = "작업 시작 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant createdAt,

        @JsonProperty("completed_at")
        @Schema(description = "작업 완료 시각(ISO-8601 UTC). 진행 중이면 null이다.")
        Instant completedAt,

        @Schema(description = "이 작업이 바꾼 리소스별 변경 내역")
        List<Change> changes,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "복구 작업일 때만 담기는 계획·결과 요약. 그 외에는 키가 빠진다.")
        RestoreSummary restore
) {

    /** 기존 호출부를 위한 생성자. 복구가 아니면 restore는 응답에서 생략된다. */
    public OperationLogDetailResponse(String operationId, String operationType, String status,
                                      String targetDocumentId, String summary,
                                      int changedResourceCount, String restoredFrom,
                                      Instant createdAt, Instant completedAt,
                                      List<Change> changes) {
        this(operationId, operationType, status, targetDocumentId, null, summary,
                changedResourceCount, restoredFrom, createdAt, completedAt, changes, null);
    }

    /**
     * @param beforeRevision 손대기 직전 버전. null이면 새로 만든 것
     * @param afterRevision  이 작업이 만든 버전. 위임·실패면 null
     * @param hunks          실제 변경분. 비교할 짝이 없거나 계산이 거부되면 생략된다
     * @param diffTooLarge   두 본문 차이가 너무 커서 계산하지 못한 경우. 개별 diff로도 볼 수 없다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "이 작업이 바꾼 리소스 하나. 값이 없는 필드는 키 자체가 빠진다.")
    public record Change(
            @Schema(description = "변경 내역 ID", example = "1")
            long id,

            @JsonProperty("resource_type")
            @Schema(description = "바뀐 리소스 종류", example = "wiki_page")
            String resourceType,

            @JsonProperty("resource_id")
            @Schema(description = "바뀐 리소스 ID")
            String resourceId,

            @JsonProperty("resource_display_name")
            @Schema(description = "변경 시점의 리소스 표시 이름")
            String resourceDisplayName,

            @JsonProperty("before_revision")
            @Schema(description = "손대기 직전 revision. 새로 만든 것이면 null이다.", example = "2")
            Long beforeRevision,

            @JsonProperty("after_revision")
            @Schema(description = "이 작업이 만든 revision. 위임·실패면 null이다.", example = "3")
            Long afterRevision,

            @JsonProperty("change_type")
            @Schema(description = "변경 종류", example = "updated")
            String changeType,

            @JsonProperty("change_summary")
            @Schema(description = "변경 요약")
            String changeSummary,

            @Schema(description = "추가된 줄 수. 저장 시점에 계산해 둔 값이다.", example = "12")
            Integer additions,

            @Schema(description = "삭제된 줄 수. 저장 시점에 계산해 둔 값이다.", example = "4")
            Integer deletions,

            @Schema(description = "실제 변경분. 비교할 짝이 없거나 계산이 거부되면 키가 빠진다.")
            List<DocumentContentDiffResponse.Hunk> hunks,

            @JsonProperty("diff_too_large")
            @Schema(description = "두 본문 차이가 너무 커서 계산하지 못한 경우 true. 개별 diff로도 볼 수 없다.",
                    example = "true")
            Boolean diffTooLarge,

            @JsonProperty("page_type")
            @Schema(description = "ingest·lint Wiki의 유형. 페이지 메타데이터가 없으면 생략된다.",
                    allowableValues = {"concept", "source"})
            String pageType
    ) {
        public static Change from(OperationChange change, ChangeDiffLoader.Diff diff) {
            return new Change(
                    change.getId(),
                    change.getResourceType().name(),
                    change.getResourceId(),
                    change.getResourceDisplayName(),
                    change.getBeforeRevision(),
                    change.getAfterRevision(),
                    change.getChangeType().name(),
                    change.getChangeSummary(),
                    change.getAdditions(),
                    change.getDeletions(),
                    diff.hunks(),
                    diff.tooLarge() ? Boolean.TRUE : null, null);
        }

        /** Wiki 작업은 제목과 분류만 전달하고 본문·변경분은 읽지 않는다. */
        public static Change wikiTitle(OperationChange change, String pageType) {
            return new Change(change.getId(), change.getResourceType().name(),
                    change.getResourceId(), change.getResourceDisplayName(),
                    null, null, change.getChangeType().name(), null,
                    null, null, null, null, pageType);
        }
    }

    /** 복구 지시서와 실제 감사 변경분 중 사용자에게 필요한 값만 노출한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "복구 작업의 계획과 실제 결과. 둘을 비교하면 계획대로 됐는지 알 수 있다.")
    public record RestoreSummary(
            @Schema(description = "복구 전에 세운 계획")
            RestorePlanSummary plan,

            @Schema(description = "실제로 일어난 결과")
            RestoreResult result
    ) {
        public static RestoreSummary from(OperationLog log, List<Change> changes,
                                          ObjectMapper objectMapper) {
            RestorePlanSummary plan = readPlan(log.getRestoreManifest(), objectMapper);
            if (plan == null) {
                plan = RestorePlanSummary.fromChanges(changes);
            }
            return new RestoreSummary(plan, RestoreResult.from(changes));
        }

        private static RestorePlanSummary readPlan(String manifest, ObjectMapper objectMapper) {
            if (manifest == null || objectMapper == null) {
                return null;
            }
            try {
                JsonNode root = objectMapper.readTree(manifest);
                JsonNode plan = root.has("plan") ? root.get("plan") : root;
                RestorePlan value = objectMapper.treeToValue(plan, RestorePlan.class);
                return RestorePlanSummary.from(value);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    @Schema(description = "복구 계획 요약")
    public record RestorePlanSummary(
            @JsonProperty("delete_count")
            @Schema(description = "삭제하기로 한 페이지 수", example = "1")
            int deleteCount,

            @JsonProperty("restore_count")
            @Schema(description = "이전 revision으로 되돌리기로 한 페이지 수", example = "2")
            int restoreCount,

            @JsonProperty("rebuild_count")
            @Schema(description = "재작성하기로 한 페이지 수", example = "3")
            int rebuildCount,

            @Schema(description = "페이지별 계획")
            List<PlanPage> pages
    ) {
        static RestorePlanSummary from(RestorePlan plan) {
            List<PlanPage> pages = plan.pages().stream()
                    .map(page -> new PlanPage(page.pageId(), page.action().name(),
                            page.contributionCount()))
                    .toList();
            return new RestorePlanSummary(plan.deleteCount(), plan.restoreCount(),
                    plan.rebuildCount(), pages);
        }

        static RestorePlanSummary fromChanges(List<Change> changes) {
            int delete = count(changes, ResourceType.wiki_page, ChangeType.deleted);
            int restore = count(changes, ResourceType.wiki_page, ChangeType.restored);
            int rebuild = count(changes, ResourceType.wiki_page, ChangeType.delegated);
            List<PlanPage> pages = changes.stream()
                    .filter(change -> change.resourceType().equals(ResourceType.wiki_page.name()))
                    .filter(change -> change.changeType().equals(ChangeType.deleted.name())
                            || change.changeType().equals(ChangeType.restored.name())
                            || change.changeType().equals(ChangeType.delegated.name()))
                    .map(change -> new PlanPage(change.resourceId(),
                            ChangeType.delegated.name().equals(change.changeType())
                                    ? "rebuild" : change.changeType(), null))
                    .toList();
            return new RestorePlanSummary(delete, restore, rebuild, pages);
        }

        private static int count(List<Change> changes, ResourceType resourceType,
                                 ChangeType changeType) {
            return (int) changes.stream()
                    .filter(change -> resourceType.name().equals(change.resourceType()))
                    .filter(change -> changeType.name().equals(change.changeType()))
                    .count();
        }
    }

    @Schema(description = "계획 상의 페이지 하나")
    public record PlanPage(
            @JsonProperty("page_id")
            @Schema(description = "Wiki 페이지 ID")
            String pageId,

            @Schema(description = "이 페이지에 하기로 한 일",
                    allowableValues = {"delete", "restore", "rebuild"}, example = "rebuild")
            String action,

            @JsonProperty("contribution_count")
            @Schema(description = "복구 후 남는 기여 수. 알 수 없으면 null이다.", example = "2")
            Integer contributionCount
    ) {}

    @Schema(description = "복구가 실제로 만들어낸 결과. 계획과 다르면 일부가 실패한 것이다.")
    public record RestoreResult(
            @JsonProperty("deleted_count")
            @Schema(description = "실제로 삭제된 페이지 수", example = "1")
            int deletedCount,

            @JsonProperty("restored_count")
            @Schema(description = "실제로 되돌아간 페이지 수", example = "2")
            int restoredCount,

            @JsonProperty("rebuilt_count")
            @Schema(description = "실제로 재작성된 페이지 수", example = "3")
            int rebuiltCount,

            @JsonProperty("failed_count")
            @Schema(description = "처리하지 못한 페이지 수", example = "0")
            int failedCount,

            @JsonProperty("removed_link_count")
            @Schema(description = "함께 지워진 페이지 간 링크 수", example = "4")
            int removedLinkCount,

            @JsonProperty("restored_link_count")
            @Schema(description = "함께 되살아난 페이지 간 링크 수", example = "2")
            int restoredLinkCount
    ) {
        static RestoreResult from(List<Change> changes) {
            return new RestoreResult(
                    count(changes, ResourceType.wiki_page, ChangeType.deleted),
                    count(changes, ResourceType.wiki_page, ChangeType.restored),
                    count(changes, ResourceType.wiki_page, ChangeType.rebuilt),
                    countFailed(changes),
                    count(changes, ResourceType.relation_link, ChangeType.link_removed),
                    count(changes, ResourceType.relation_link, ChangeType.link_restored));
        }

        private static int countFailed(List<Change> changes) {
            return (int) changes.stream()
                    .filter(change -> change.changeType().equals(ChangeType.rebuild_failed.name())
                            || change.changeType().equals(ChangeType.action_failed.name()))
                    .map(Change::resourceId)
                    .distinct()
                    .count();
        }

        private static int count(List<Change> changes, ResourceType resourceType,
                                 ChangeType changeType) {
            return (int) changes.stream()
                    .filter(change -> resourceType.name().equals(change.resourceType()))
                    .filter(change -> changeType.name().equals(change.changeType()))
                    .map(Change::resourceId)
                    .distinct()
                    .count();
        }
    }

    public static OperationLogDetailResponse from(OperationLog log, List<Change> changes) {
        return from(log, changes, null);
    }

    public static OperationLogDetailResponse from(OperationLog log, List<Change> changes,
                                                  ObjectMapper objectMapper) {
        return new OperationLogDetailResponse(
                log.getOperationId(),
                log.getOperationType().name(),
                log.getStatus().name(),
                log.getTargetDocumentId(),
                log.getTargetDisplayName(),
                log.getSummary(),
                log.getChangedResourceCount(),
                log.getRestoredFrom(),
                log.getCreatedAt(),
                log.getCompletedAt(),
                changes,
                log.getOperationType() == OperationType.restore
                        ? RestoreSummary.from(log, changes, objectMapper) : null);
    }
}
