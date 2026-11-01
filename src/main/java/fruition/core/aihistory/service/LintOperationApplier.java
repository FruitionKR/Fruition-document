package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.dto.OperationResultResponse;
import fruition.core.aihistory.exception.InvalidCallbackPayloadException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageContributionId;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * lint 결과를 새 Wiki revision, 작업 변경 이력, 기여 원장 행으로 저장한다.
 *
 * <p>원장 행이 있어야 이후 편입·lint 복원의 재조립이 lint 변경(하위 항목 분리·다시 합치기)을 재생한다.
 */
@Component
public class LintOperationApplier {

    private static final Logger log = LoggerFactory.getLogger(LintOperationApplier.class);

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final PipelineWikiStateRequester wikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final LineCounter lineCounter;

    public LintOperationApplier(OperationLogRepository operationLogRepository,
                                OperationChangeRepository operationChangeRepository,
                                PipelineWikiStateRequester wikiStateRequester,
                                WikiPageVersionRepository versionRepository,
                                WikiPageContributionRepository contributionRepository,
                                LineCounter lineCounter) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.wikiStateRequester = wikiStateRequester;
        this.versionRepository = versionRepository;
        this.contributionRepository = contributionRepository;
        this.lineCounter = lineCounter;
    }

    /**
     * @param ledger 검증을 마친 {@code ledger_contributions}. 목록 순서대로 원장에 더한다
     */
    @Transactional
    public OperationResultResponse apply(String operationId, OperationResultRequest request,
                                         List<LoadedPage> loaded,
                                         List<OperationResultRequest.LedgerContribution> ledger,
                                         String payloadHash, Instant now) {
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        Map<String, LoadedPage> pages = new HashMap<>();
        loaded.forEach(page -> pages.put(page.pageId(), page));
        Set<String> deleted = Set.copyOf(request.deletedPagesOrEmpty());
        validateLedger(operation, ledger, pages.keySet());

        // 같은 페이지에 대한 결과가 동시에 오면 revision 채번이 겹친다. OperationApplier와 같은 page_id 순서로 잠근다.
        Set<String> pageIds = new TreeSet<>(pages.keySet());
        pageIds.addAll(deleted);
        for (String pageId : pageIds) {
            List<OperationResultRequest.LedgerContribution> rows = ledger.stream()
                    .filter(row -> row.pageId().equals(pageId))
                    .toList();
            applyPage(operation, pageId, pages.get(pageId), rows, deleted.contains(pageId), now);
        }

        // 반영 건수로 실패와 부분 성공을 가르는 규칙은 OperationApplier와 같다. 0건이면
        // 되돌릴 대상이 없어 "일부만 반영했습니다"가 사실과 다르고, 목록 필터도 통과해 버린다.
        OperationStatus status = request.isFailure()
                ? (loaded.isEmpty() ? OperationStatus.failed : OperationStatus.partially_succeeded)
                : OperationStatus.succeeded;
        if (status != OperationStatus.succeeded) {
            // 오류 원문은 운영자용이라 로그로만 남기고, 저장하는 요약은 사용자용 문구로 쓴다.
            log.warn("[lint 실패] operationId={} status={} reason={}", operationId, status, request.summary());
        }
        String summary = status == OperationStatus.succeeded
                ? request.summary()
                : OperationFailureSummary.of(OperationType.lint, status);
        operation.complete(status, summary, loaded.size(), payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), loaded.size());
    }

    /**
     * 원장 행은 이 lint 자기 기록이거나, 같은 워크스페이스의 다른 작업 기록을 이 lint가 이은 것이어야 한다.
     * 행을 더하는 페이지는 이 lint가 새 revision을 남기는 페이지여야 한다. 그래야 행의 순서 값이
     * 다음 편입의 revision보다 앞선다.
     */
    private void validateLedger(OperationLog operation,
                                List<OperationResultRequest.LedgerContribution> ledger,
                                Set<String> changedPageIds) {
        String lintId = operation.getOperationId();
        for (OperationResultRequest.LedgerContribution row : ledger) {
            if (!changedPageIds.contains(row.pageId())) {
                throw new InvalidCallbackPayloadException(
                        "변경 페이지에 없는 원장 행입니다: pageId=" + row.pageId());
            }
            if (row.linkedByOperationId() == null) {
                if (!lintId.equals(row.operationId())) {
                    throw new InvalidCallbackPayloadException(
                            "lint 기록의 작업 id가 다릅니다: pageId=" + row.pageId());
                }
                continue;
            }
            boolean sameWorkspace = row.operationId() != null && !lintId.equals(row.operationId())
                    && lintId.equals(row.linkedByOperationId())
                    && operationLogRepository.findById(row.operationId())
                            .map(linked -> linked.getWorkspaceId().equals(operation.getWorkspaceId()))
                            .orElse(false);
            if (!sameWorkspace) {
                throw new InvalidCallbackPayloadException(
                        "이을 수 없는 작업 기록입니다: pageId=" + row.pageId()
                                + " operationId=" + row.operationId());
            }
        }
    }

    private void applyPage(OperationLog operation, String pageId, LoadedPage page,
                           List<OperationResultRequest.LedgerContribution> rows,
                           boolean deletedPage, Instant now) {
        versionRepository.lockPage(pageId);
        var wikiPage = wikiStateRequester.lookup(List.of(pageId), operation.getWorkspaceId()).stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCallbackPayloadException(
                        "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        if (!wikiPage.workspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }
        // 재전송이면 이 lint가 이미 만든 revision이 있다. 원장 행과 비활성화도 그때 함께 반영했다.
        if (page != null && versionRepository
                .findFirstByIdPageIdAndOperationId(pageId, operation.getOperationId()).isPresent()) {
            return;
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        // 예전 버전이 있어도 마지막 변경이 삭제면 지워진 페이지를 이 lint가 다시 만든 것이다(예: lint 복원으로
        // 지운 자식을 다음 lint가 다시 나눔). created로 남겨야 이 lint를 복원할 때 다시 지운다.
        // 기여 수로 가르지 않는다. 문서를 휴지통으로 옮기면 기여만 꺼지고 개념 페이지는 남는다.
        if (page != null && previous.isPresent() && operationChangeRepository
                .findTopByResourceTypeAndResourceIdOrderByIdDesc(ResourceType.wiki_page, pageId)
                .map(change -> change.getChangeType() == ChangeType.deleted)
                .orElse(false)) {
            previous = Optional.empty();
        }
        long maxRevision = versionRepository.findMaxRevision(pageId);
        // 행마다 다른 순서 값이 있어야 재생 순서가 정해진다. revision을 행 수만큼 넘기고 새 버전은 마지막
        // 값에 둔다. 그 사이 번호에는 버전이 없다.
        long sequence = maxRevision;
        for (OperationResultRequest.LedgerContribution row : rows) {
            record(operation, row, ++sequence, now);
        }

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        if (page != null) {
            long revision = Math.max(sequence, maxRevision + 1);
            int contributionCount = (int) contributionRepository.countByIdPageIdAndActiveTrue(pageId);
            versionRepository.save(new WikiPageVersion(
                    pageId, revision, contributionCount, page.markdown(), page.markdownKey(),
                    page.contentHash(), operation.getOperationId(), operation.getUserId(), now));
            LineCounter.LineCount lines = lineCounter.count(pageId, beforeRevision,
                    previous.map(WikiPageVersion::getMarkdown).orElse(null), revision, page.markdown());
            operationChangeRepository.save(new OperationChange(
                    operation.getOperationId(), ResourceType.wiki_page, pageId,
                    wikiPage.title(), beforeRevision, revision,
                    beforeRevision == null ? ChangeType.created : ChangeType.updated,
                    null, lines.additions(), lines.deletions()));
            beforeRevision = revision;
        }
        // 다시 합치기로 지운 자식. 삭제는 이 lint 기록을 재생한 결과라 기존 기여는 끄지 않는다(ai ADR 0042).
        // 끄면 그 사이 취소한 편입을 lint 복원이 다시 살려야 하는지 가릴 수 없다.
        if (deletedPage && !operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                operation.getOperationId(), pageId, ChangeType.deleted)) {
            operationChangeRepository.save(new OperationChange(
                    operation.getOperationId(), ResourceType.wiki_page, pageId,
                    wikiPage.title(), beforeRevision, null, ChangeType.deleted,
                    "부모 페이지로 합쳐 삭제했습니다.", null, null));
        }
    }

    /** 같은 (페이지, 작업) 행이 켜져 있으면 재전송이다. 꺼져 있으면 이 lint가 다시 잇는다. */
    private void record(OperationLog operation, OperationResultRequest.LedgerContribution row,
                        long sequence, Instant now) {
        String lintId = operation.getOperationId();
        Optional<WikiPageContribution> existing = contributionRepository.findById(
                new WikiPageContributionId(row.pageId(), row.operationId()));
        if (existing.isPresent()) {
            if (!existing.get().isActive()) {
                existing.get().relink(row.documentId(), sequence, row.contributionKey(), lintId);
            }
            return;
        }
        contributionRepository.save(WikiPageContribution.lint(row.pageId(), row.operationId(),
                row.documentId(), sequence, row.contributionKey(), lintId, now));
    }

    public record LoadedPage(
            String pageId,
            String markdownKey,
            String markdown,
            String contentHash
    ) {}
}
