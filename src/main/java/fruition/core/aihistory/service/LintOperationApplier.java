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
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** lint 결과를 새 Wiki revision과 작업 변경 이력으로 저장한다. */
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

    @Transactional
    public OperationResultResponse apply(String operationId, OperationResultRequest request,
                                         List<LoadedPage> loaded, String payloadHash, Instant now) {
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        List<LoadedPage> ordered = loaded.stream()
                .sorted(Comparator.comparing(LoadedPage::pageId))
                .toList();

        for (LoadedPage page : ordered) {
            applyPage(operation, page, now);
        }

        // 반영 건수로 실패와 부분 성공을 가르는 규칙은 OperationApplier와 같다. 0건이면
        // 되돌릴 대상이 없어 "일부만 반영했습니다"가 사실과 다르고, 목록 필터도 통과해 버린다.
        OperationStatus status = request.isFailure()
                ? (ordered.isEmpty() ? OperationStatus.failed : OperationStatus.partially_succeeded)
                : OperationStatus.succeeded;
        if (status != OperationStatus.succeeded) {
            // 오류 원문은 운영자용이라 로그로만 남기고, 저장하는 요약은 사용자용 문구로 쓴다.
            log.warn("[lint 실패] operationId={} status={} reason={}", operationId, status, request.summary());
        }
        String summary = status == OperationStatus.succeeded
                ? request.summary()
                : OperationFailureSummary.of(OperationType.lint, status);
        operation.complete(status, summary, ordered.size(), payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), ordered.size());
    }

    private void applyPage(OperationLog operation, LoadedPage page, Instant now) {
        String pageId = page.pageId();
        versionRepository.lockPage(pageId);
        var wikiPage = wikiStateRequester.lookup(List.of(pageId), operation.getWorkspaceId()).stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCallbackPayloadException(
                        "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        if (!wikiPage.workspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        long revision = versionRepository.findMaxRevision(pageId) + 1;
        int contributionCount = (int) contributionRepository.countByIdPageIdAndActiveTrue(pageId);

        versionRepository.save(new WikiPageVersion(
                pageId, revision, contributionCount, page.markdown(), page.markdownKey(),
                page.contentHash(), operation.getOperationId(), operation.getUserId(), now));

        Long beforeRevision = previous.map(WikiPageVersion::getRevision).orElse(null);
        LineCounter.LineCount lines = lineCounter.count(pageId, beforeRevision,
                previous.map(WikiPageVersion::getMarkdown).orElse(null), revision, page.markdown());
        operationChangeRepository.save(new OperationChange(
                operation.getOperationId(), ResourceType.wiki_page, pageId,
                wikiPage.title(), beforeRevision, revision,
                beforeRevision == null ? ChangeType.created : ChangeType.updated,
                null, lines.additions(), lines.deletions()));
    }

    public record LoadedPage(
            String pageId,
            String markdownKey,
            String markdown,
            String contentHash
    ) {}
}
