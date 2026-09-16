package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 복구 계획을 DB에 반영한다. <b>한 트랜잭션</b>으로 처리한다.
 *
 * <p>Backend는 저장소에도 {@code wiki_pages}에도 쓰지 않는다. 되돌릴 revision의 본문이 이미
 * {@code wiki_page_versions}에 있으므로 그것을 새 revision으로 쌓으면 그 자체가 현재 본문이 된다.
 */
@Component
public class RestoreApplier {

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final PipelineWikiStateRequester wikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final WikiPageContributionRepository contributionRepository;

    public RestoreApplier(OperationLogRepository operationLogRepository,
                          OperationChangeRepository operationChangeRepository,
                          PipelineWikiStateRequester wikiStateRequester,
                          WikiPageVersionRepository versionRepository,
                          WikiPageContributionRepository contributionRepository) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.wikiStateRequester = wikiStateRequester;
        this.versionRepository = versionRepository;
        this.contributionRepository = contributionRepository;
    }

    @Transactional(noRollbackFor = RestorePreviewStaleException.class)
    public void apply(OperationLog restore, RestorePlan plan, Set<String> excludedOperationIds,
                      Map<String, List<String>> expectedContributionSignatures, Instant now) {

        // 여러 복구가 동시에 실행될 때 교착을 피하려고 page_id 순서로 잠근다.
        List<String> pageIds = plan.pages().stream()
                .map(PageRestorePlan::pageId)
                .sorted()
                .toList();
        for (String pageId : pageIds) {
            versionRepository.lockPage(pageId);
        }
        Map<String, String> pageTitles = new LinkedHashMap<>();
        for (PipelineWikiStateRequester.WikiPageSnapshot page
                : wikiStateRequester.lookup(pageIds, restore.getWorkspaceId())) {
            pageTitles.put(page.id(), page.title());
        }
        Set<String> existingPageIds = pageTitles.keySet();
        if (!existingPageIds.containsAll(pageIds)) {
            throw new InvalidRestoreRequestException("Wiki 페이지를 찾을 수 없습니다.");
        }

        // 잠금을 잡은 뒤에도 계획을 만들 때 본 상태 그대로인지 다시 확인한다. 잠금 전에는 동시에
        // 들어온 ingest가 기여를 바꿔도 알 방법이 없어, 그 상태로 그냥 반영하면 새 기여가 조용히
        // 덮어써진다.
        verifyContributionsUnchanged(pageIds, expectedContributionSignatures);

        // 제외 대상 기여를 끈다. 행은 지우지 않는다.
        // 지우면 연속 복구에서 이전에 제외한 기여가 다시 살아난다.
        deactivate(pageIds, excludedOperationIds, restore.getOperationId());

        for (PageRestorePlan page : plan.pages()) {
            switch (page.action()) {
                case restore -> restorePage(restore, page, pageTitles.get(page.pageId()), now);
                case delete -> deletePage(restore, page, pageTitles.get(page.pageId()), now);
                case rebuild -> delegate(restore, page, pageTitles.get(page.pageId()));
            }
        }

        restore.moveTo(OperationStatus.rebuilding);
        operationLogRepository.save(restore);
    }

    private void deactivate(List<String> pageIds, Set<String> excluded, String restoreOperationId) {
        for (WikiPageContribution contribution : contributionRepository.findByPageIds(pageIds)) {
            if (contribution.isActive() && excluded.contains(contribution.getIngestOperationId())) {
                contribution.deactivate(restoreOperationId);
            }
        }
    }

    /**
     * 잠금 아래서 다시 읽은 기여가 계획을 만들 때 넘겨받은 상태와 같은지 확인한다.
     * 다르면 그사이 다른 작업이 끼어든 것이므로 이 계획을 그대로 반영하면 안 된다.
     */
    private void verifyContributionsUnchanged(List<String> pageIds,
                                              Map<String, List<String>> expected) {
        Map<String, List<WikiPageContribution>> current = new LinkedHashMap<>();
        for (WikiPageContribution contribution : contributionRepository.findByPageIds(pageIds)) {
            current.computeIfAbsent(contribution.getPageId(), key -> new ArrayList<>()).add(contribution);
        }
        for (String pageId : pageIds) {
            if (!expected.getOrDefault(pageId, List.of()).equals(signature(current.get(pageId)))) {
                throw new RestorePreviewStaleException();
            }
        }
    }

    /** 페이지 하나의 기여 상태를 비교 가능한 형태로 정규화한다. 순서·활성 여부까지 담는다. */
    private List<String> signature(List<WikiPageContribution> contributions) {
        if (contributions == null) {
            return List.of();
        }
        return contributions.stream()
                .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                .map(c -> c.getIngestOperationId() + ":" + c.getSequenceRevision()
                        + ":" + (c.isActive() ? "1" : "0"))
                .toList();
    }

    /** 되돌릴 revision의 본문과 object key를 재사용해 새 revision으로 쌓는다. */
    private void restorePage(OperationLog restore, PageRestorePlan page,
                             String resourceDisplayName, Instant now) {
        String pageId = page.pageId();
        WikiPageVersion target = versionRepository
                .findById(new WikiPageVersionId(pageId, page.targetRevision()))
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "되돌릴 버전을 찾을 수 없습니다: pageId=" + pageId + " revision=" + page.targetRevision()));

        long maxRevision = versionRepository.findMaxRevision(pageId);
        long revision = maxRevision + 1;

        versionRepository.save(new WikiPageVersion(
                pageId, revision, page.contributionCount(),
                target.getMarkdown(), target.getMarkdownKey(), target.getContentHash(),
                restore.getOperationId(), restore.getUserId(), now));

        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.wiki_page, pageId,
                resourceDisplayName, maxRevision, revision, ChangeType.restored,
                "revision " + page.targetRevision() + " 내용으로 되돌렸습니다.", null, null));
    }

    /**
     * 삭제 기록만 남긴다. 받치는 기여가 하나도 없는 상태가 곧 삭제이므로 따로 표시할 것이 없다.
     *
     * <p>{@code wiki_pages}와 링크 테이블은 llmPipeline 소유라 건드리지 않는다. 조립 지시서의
     * {@code deleted_pages}로 알려주면 llmPipeline이 링크와 임베딩을 정리한다.
     */
    private void deletePage(OperationLog restore, PageRestorePlan page,
                            String resourceDisplayName, Instant now) {
        String pageId = page.pageId();
        long maxRevision = versionRepository.findMaxRevision(pageId);

        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.wiki_page, pageId,
                resourceDisplayName, maxRevision == 0 ? null : maxRevision, null, ChangeType.deleted,
                "받치는 기여가 남지 않아 삭제했습니다.", null, null));
    }

    /** 본문을 건드리지 않고 llmPipeline에 맡겼다는 기록만 남긴다. */
    private void delegate(OperationLog restore, PageRestorePlan page, String resourceDisplayName) {
        long maxRevision = versionRepository.findMaxRevision(page.pageId());
        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.wiki_page, page.pageId(),
                resourceDisplayName, maxRevision == 0 ? null : maxRevision, null, ChangeType.delegated,
                "남은 기여 " + page.contributionCount() + "개로 재작성을 맡겼습니다.", null, null));
    }
}
