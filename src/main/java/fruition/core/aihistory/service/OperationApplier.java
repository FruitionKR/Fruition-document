package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 읽어둔 결과를 DB에 반영한다. 저장소 읽기를 마친 뒤 <b>한 트랜잭션</b>으로 처리한다.
 *
 * <p>{@link OperationIngestService}와 분리한 이유는 자기 호출로는 {@code @Transactional}이
 * 걸리지 않기 때문이다.
 */
@Component
public class OperationApplier {

    private static final Logger log = LoggerFactory.getLogger(OperationApplier.class);

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final PipelineWikiStateRequester wikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final LineCounter lineCounter;

    public OperationApplier(OperationLogRepository operationLogRepository,
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

        // 같은 페이지에 대한 콜백이 동시에 오면 revision 채번이 겹친다.
        // 교착을 피하려고 page_id 오름차순으로 잠근다. RestoreApplier와 같은 순서다.
        List<LoadedPage> ordered = loaded.stream()
                .sorted(Comparator.comparing(LoadedPage::pageId))
                .toList();

        int recorded = 0;
        for (LoadedPage page : ordered) {
            if (applyPage(operation, page, now)) {
                recorded++;
            }
        }

        // 부분 실패여도 이미 만든 페이지는 기록한다.
        // 안 그러면 Wiki에는 있는데 로그에 없는 페이지가 영영 복구 대상에서 빠진다.
        OperationStatus status = request.isFailure()
                ? (recorded == 0 ? OperationStatus.failed : OperationStatus.partially_succeeded)
                : OperationStatus.succeeded;
        operation.complete(status, summaryFor(operation, request, status), recorded, payloadHash, now);
        return new OperationResultResponse(operationId, status.name(), recorded);
    }

    /**
     * 성공 요약은 llmPipeline이 만든 문구를 그대로 쓴다. 실패 요약은 오류 원문이 실려 오므로
     * 사용자용 문구로 바꾸고 원문은 로그로만 남긴다.
     */
    private String summaryFor(OperationLog operation, OperationResultRequest request, OperationStatus status) {
        if (status == OperationStatus.succeeded) {
            return request.summary();
        }
        log.warn("[AI 작업 실패 확정] operationId={} type={} status={} reason={}",
                operation.getOperationId(), operation.getOperationType(), status, request.summary());
        return OperationFailureSummary.of(operation.getOperationType(), status);
    }

    /** @return 적재했으면 true. 같은 작업의 재전송이면 건너뛴다 */
    private boolean applyPage(OperationLog operation, LoadedPage page, Instant now) {
        String pageId = page.pageId();
        // 행을 바꾸지는 않지만, 같은 페이지 콜백이 동시에 와도 revision 채번이 겹치지 않도록 잠근다.
        versionRepository.lockPage(pageId);
        var wikiPage = wikiStateRequester.lookup(List.of(pageId), operation.getWorkspaceId()).stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCallbackPayloadException(
                        "Wiki 페이지를 찾을 수 없습니다: pageId=" + pageId));
        if (!wikiPage.workspaceId().equals(operation.getWorkspaceId())) {
            throw new InvalidCallbackPayloadException(
                    "다른 워크스페이스의 페이지입니다: pageId=" + pageId);
        }

        // 재전송 판정은 기여 유무로 한다. 본문 해시로 가르면, 다른 문서가 우연히 같은 내용을
        // 만들었을 때 재전송으로 오인해 그 문서의 기여가 원장에서 빠진다. 그러면 나중에 앞
        // 문서를 되돌릴 때 받치는 문서가 남았는데도 페이지가 삭제된다.
        if (contributionRepository.existsById(
                new WikiPageContributionId(pageId, operation.getOperationId()))) {
            return false;
        }

        Optional<WikiPageVersion> previous =
                versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(pageId);
        long revision = versionRepository.findMaxRevision(pageId) + 1;

        // 기여를 먼저 넣어야 그 시점 기여 수가 나온다. 그 값이 버전 행에 들어간다.
        contributionRepository.save(new WikiPageContribution(
                pageId, operation.getOperationId(), operation.getTargetDocumentId(),
                revision, page.contributionKey(), now));
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
        return true;
    }

    /** 저장소에서 읽어 검증까지 마친 페이지 하나. */
    public record LoadedPage(String pageId, String markdownKey, String contributionKey,
                             String markdown, String contentHash) {}
}
