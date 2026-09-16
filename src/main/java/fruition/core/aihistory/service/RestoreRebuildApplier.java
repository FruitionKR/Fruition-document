package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.domain.RestoreAction;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.OperationResultResponse;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 재조립 결과를 DB에 반영해 복구 작업을 끝낸다. 저장소 읽기를 마친 뒤 <b>한 트랜잭션</b>으로 처리한다.
 *
 * <p>ingest 적재와 다른 점은 <b>기여를 만들지 않는다</b>는 것이다. 조립에 쓴 조각은 이미
 * {@code wiki_page_contributions}에 있고 복구가 살려둔 것들이다. 따라서 {@code contribution_count}도
 * 다시 세지 않고 복구가 보관해 둔 {@code restore_manifest}에서 꺼내 쓴다. 그사이 새 ingest가
 * 들어와도 목표값이 흔들리지 않게 하기 위해서다.
 */
@Component
public class RestoreRebuildApplier {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");
    private static final Set<String> SAFE_FAILURE_CODES = Set.of(
            "assembly_failed", "concept_rebuild_failed", "contribution_missing",
            "operation_log_missing", "source_snapshot_missing");

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final PipelineWikiStateRequester wikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final LineCounter lineCounter;
    private final ObjectMapper objectMapper;

    public RestoreRebuildApplier(OperationLogRepository operationLogRepository,
                                 OperationChangeRepository operationChangeRepository,
                                 PipelineWikiStateRequester wikiStateRequester,
                                 WikiPageVersionRepository versionRepository,
                                 LineCounter lineCounter,
                                 ObjectMapper objectMapper) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.wikiStateRequester = wikiStateRequester;
        this.versionRepository = versionRepository;
        this.lineCounter = lineCounter;
        this.objectMapper = objectMapper;
    }

    /** {@code recorded_changes}는 이 복구 작업이 남긴 전체 변경내역 수다. 복구가 먼저 만든 삭제·복원·위임 기록을 포함한다. */
    @Transactional
    public OperationResultResponse apply(String operationId, OperationResultRequest request,
                                         List<RebuiltPage> loaded, String payloadHash, Instant now) {
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));

        RestorePlan plan = restorePlan(operation);
        Map<String, Integer> targetCounts = targetContributionCounts(plan);
        Map<String, String> pageTitles = pageTitles(operationId);
        validateDeletedPages(request, plan);
        Map<String, PipelineWikiStateRequester.WikiPageSnapshot> pageSnapshots = pageSnapshots(
                operation, loaded, request.deletedPagesOrEmpty());
        pageSnapshots.forEach((pageId, page) -> pageTitles.putIfAbsent(pageId, page.title()));
        for (RebuiltPage page : loaded) {
            applyPage(operation, page, targetCounts, pageSnapshots.get(page.pageId()), now);
        }
        for (OperationResultRequest.FailedPage failed : request.failedPagesOrEmpty()) {
            recordFailure(operation, failed, targetCounts, pageTitles);
        }
        recordReportedChanges(operation, request, pageTitles);
        recordFailedActions(operation, request);

        int changed = (int) operationChangeRepository.countByOperationId(operationId);
        int changedPages = (int) operationChangeRepository.findByOperationIdOrderByIdAsc(operationId).stream()
                .filter(change -> change.getResourceType() == ResourceType.wiki_page)
                .filter(change -> change.getChangeType() == ChangeType.restored
                        || change.getChangeType() == ChangeType.rebuilt)
                .map(OperationChange::getResourceId)
                .distinct()
                .count();
        OperationStatus status = request.failedPagesOrEmpty().isEmpty() && !request.isFailure()
                ? OperationStatus.succeeded
                : OperationStatus.partially_succeeded;
        String summary = resultSummary(request, changedPages);
        operation.complete(status, summary, changed, payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), changed);
    }

    /** llmPipeline 결과의 페이지·링크·실패 수를 조회 화면에서 바로 읽을 수 있게 남긴다. */
    private String resultSummary(OperationResultRequest request, int changed) {
        OperationResultRequest.LinkChanges links = request.linkChangesOrEmpty();
        return "페이지 변경 " + changed + "건"
                + " · 삭제 " + request.deletedPagesOrEmpty().size() + "건"
                + " · 링크 제거 " + links.removedLinks().size() + "건"
                + " · 링크 복원 " + links.restoredLinks().size() + "건"
                + " · 실패 " + (request.failedPagesOrEmpty().size()
                + request.failedActionsOrEmpty().size()) + "건";
    }

    /** 실행 단계에서 빠진 삭제 기록을 보완하고 llmPipeline이 처리한 링크 변경을 감사 로그로 남긴다. */
    private void recordReportedChanges(OperationLog operation, OperationResultRequest request,
                                       Map<String, String> pageTitles) {
        for (String pageId : request.deletedPagesOrEmpty()) {
            if (!alreadyRecorded(operation, pageId, ChangeType.deleted)) {
                long revision = versionRepository.findMaxRevision(pageId);
                operationChangeRepository.save(new OperationChange(
                        operation.getOperationId(), ResourceType.wiki_page, pageId,
                        pageTitles.get(pageId), revision == 0 ? null : revision, null, ChangeType.deleted,
                        "llmPipeline이 페이지 삭제를 완료했습니다.", null, null));
            }
        }

        OperationResultRequest.LinkChanges links = request.linkChangesOrEmpty();
        for (OperationResultRequest.Link link : links.removedLinks()) {
            recordLink(operation, link, ChangeType.link_removed, "링크를 제거했습니다.");
        }
        for (OperationResultRequest.Link link : links.restoredLinks()) {
            recordLink(operation, link, ChangeType.link_restored, "링크를 복원했습니다.");
        }
    }

    private void recordFailedActions(OperationLog operation, OperationResultRequest request) {
        for (OperationResultRequest.FailedAction failed : request.failedActionsOrEmpty()) {
            String action = safeIdentifier(failed.action());
            String resourceId = safeIdentifier(failed.resourceId());
            if (action == null || resourceId == null
                    || operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                    operation.getOperationId(), resourceId, ChangeType.action_failed)) {
                continue;
            }
            String reason = safeFailureCode(failed.reason());
            String summary = reason == null ? action : action + ": " + reason;
            operationChangeRepository.save(new OperationChange(
                    operation.getOperationId(), ResourceType.action, resourceId,
                    null, null, ChangeType.action_failed, summary, null, null));
        }
    }

    private void recordLink(OperationLog operation, OperationResultRequest.Link link,
                            ChangeType changeType, String summary) {
        String resourceId = link.source() + "|" + link.relation() + "|" + link.target();
        if (!alreadyRecorded(operation, resourceId, changeType)) {
            operationChangeRepository.save(new OperationChange(
                    operation.getOperationId(), ResourceType.relation_link, resourceId,
                    null, null, changeType, summary, null, null));
        }
    }

    private boolean alreadyRecorded(OperationLog operation, String resourceId, ChangeType changeType) {
        return operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                operation.getOperationId(), resourceId, changeType);
    }

    /**
     * 지시서에 담은 페이지별 목표 기여 수. 여기 없는 페이지가 결과에 오면 요청하지 않은 것이므로 거절한다.
     *
     * <p>{@code restore}로 판정한 페이지도 포함한다. Backend가 이미 되돌렸지만 llmPipeline도 자기
     * 사본을 만들어 결과에 실어 보내기 때문이다(source page가 그렇다). 본문이 같아 아래에서
     * {@code content_hash} 비교로 걸러지므로 새 revision이 생기지는 않는다.
     */
    private RestorePlan restorePlan(OperationLog operation) {
        if (operation.getRestoreManifest() == null) {
            throw new InvalidCallbackPayloadException(
                    "복구 지시서가 없는 작업입니다: operationId=" + operation.getOperationId());
        }
        try {
            var root = objectMapper.readTree(operation.getRestoreManifest());
            return objectMapper.treeToValue(root.has("plan") ? root.get("plan") : root, RestorePlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("복구 지시서를 읽지 못했습니다: operationId="
                    + operation.getOperationId(), e);
        }
    }

    private Map<String, Integer> targetContributionCounts(RestorePlan plan) {
        Map<String, Integer> counts = new HashMap<>();
        for (PageRestorePlan page : plan.pages()) {
            if (page.action() == RestoreAction.rebuild || page.action() == RestoreAction.restore) {
                counts.put(page.pageId(), page.contributionCount());
            }
        }
        return counts;
    }

    private void validateDeletedPages(OperationResultRequest request, RestorePlan plan) {
        Set<String> targets = plan.byAction(RestoreAction.delete).stream()
                .map(PageRestorePlan::pageId)
                .collect(java.util.stream.Collectors.toSet());
        for (String pageId : request.deletedPagesOrEmpty()) {
            if (!targets.contains(pageId)) {
                throw new InvalidCallbackPayloadException(
                        "복구 지시서에 없는 삭제 페이지입니다: pageId=" + pageId);
            }
        }
    }

    private void applyPage(OperationLog operation, RebuiltPage page,
                           Map<String, Integer> targetCounts,
                           PipelineWikiStateRequester.WikiPageSnapshot wikiPage,
                           Instant now) {
        String pageId = page.pageId();
        int contributionCount = targetCount(targetCounts, pageId);

        versionRepository.lockPage(pageId);
        if (wikiPage == null) {
            throw new InvalidCallbackPayloadException(
                    "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId);
        }
        if (!wikiPage.workspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        // 같은 결과가 다시 오면 여기서 멈춘다. 재조립 본문은 복구 시점의 본문과 다르므로
        // 해시가 같다는 것은 이미 반영했다는 뜻이다.
        if (previous.isPresent() && previous.get().getContentHash().equals(page.contentHash())) {
            return;
        }

        long revision = versionRepository.findMaxRevision(pageId) + 1;
        versionRepository.save(new WikiPageVersion(
                pageId, revision, contributionCount, page.markdown(), page.markdownKey(),
                page.contentHash(), operation.getOperationId(), operation.getUserId(), now));

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        LineCounter.LineCount lines = lineCounter.count(pageId, beforeRevision,
                previous.map(WikiPageVersion::getMarkdown).orElse(null), revision, page.markdown());
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                wikiPage.title(), beforeRevision, revision, ChangeType.rebuilt,
                "남은 기여 " + contributionCount + "개로 다시 만들었습니다.",
                lines.additions(), lines.deletions()));
    }

    /**
     * 실패는 본문을 건드리지 않고 기록만 남긴다. 페이지는 복구 직전 내용 그대로 남아 다음 lint에 맡긴다.
     */
    private void recordFailure(OperationLog operation, OperationResultRequest.FailedPage failed,
                               Map<String, Integer> targetCounts, Map<String, String> pageTitles) {
        String pageId = failed.pageId();
        targetCount(targetCounts, pageId);
        if (operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                operation.getOperationId(), pageId, ChangeType.rebuild_failed)) {
            return;
        }
        long maxRevision = versionRepository.findMaxRevision(pageId);
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                pageTitles.get(pageId), maxRevision == 0 ? null : maxRevision, null,
                ChangeType.rebuild_failed,
                safeFailureCode(failed.reason()), null, null));
    }

    private Map<String, String> pageTitles(String operationId) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (OperationChange change : operationChangeRepository
                .findByOperationIdOrderByIdAsc(operationId)) {
            if (change.getResourceType() == ResourceType.wiki_page) {
                titles.putIfAbsent(change.getResourceId(), change.getResourceDisplayName());
            }
        }
        return titles;
    }

    private Map<String, PipelineWikiStateRequester.WikiPageSnapshot> pageSnapshots(
            OperationLog operation, List<RebuiltPage> loaded, List<String> deletedPageIds) {
        List<String> pageIds = java.util.stream.Stream.concat(
                        loaded.stream().map(RebuiltPage::pageId), deletedPageIds.stream())
                .distinct()
                .toList();
        Map<String, PipelineWikiStateRequester.WikiPageSnapshot> snapshots = new LinkedHashMap<>();
        for (PipelineWikiStateRequester.WikiPageSnapshot page
                : wikiStateRequester.lookup(pageIds, operation.getWorkspaceId())) {
            snapshots.put(page.id(), page);
        }
        return snapshots;
    }

    private String safeIdentifier(String value) {
        return value != null && SAFE_IDENTIFIER.matcher(value).matches() ? value : null;
    }

    private String safeFailureCode(String value) {
        return SAFE_FAILURE_CODES.contains(value) ? value : null;
    }

    private int targetCount(Map<String, Integer> targetCounts, String pageId) {
        Integer count = targetCounts.get(pageId);
        if (count == null) {
            throw new InvalidCallbackPayloadException(
                    "재조립을 요청하지 않은 페이지입니다: pageId=" + pageId);
        }
        return count;
    }

    /** 저장소에서 읽어 해시 검증까지 마친 재조립 페이지 하나. */
    public record RebuiltPage(String pageId, String markdownKey, String markdown, String contentHash) {}
}
