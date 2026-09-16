package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 현재 llmPipeline 계약에 맞춰 lint 삭제·재조립 계획을 만든다. */
@Component
public class LintRestorePlanner {

    private final OperationChangeRepository operationChangeRepository;
    private final WikiPageContributionRepository contributionRepository;

    public LintRestorePlanner(OperationChangeRepository operationChangeRepository,
                              WikiPageContributionRepository contributionRepository) {
        this.operationChangeRepository = operationChangeRepository;
        this.contributionRepository = contributionRepository;
    }

    public Context plan(OperationLog target) {
        List<OperationChange> changes = operationChangeRepository
                .findByOperationIdOrderByIdAsc(target.getOperationId()).stream()
                .filter(change -> change.getResourceType() == ResourceType.wiki_page)
                .filter(change -> change.getChangeType() == ChangeType.created
                        || change.getChangeType() == ChangeType.updated)
                .toList();
        if (changes.isEmpty()) {
            return new Context(new RestorePlan(List.of()), Map.of());
        }

        for (OperationChange change : changes) {
            if (change.getId() == null || operationChangeRepository
                    .existsByResourceIdAndIdGreaterThan(change.getResourceId(), change.getId())) {
                throw new InvalidRestoreRequestException(
                        "대상 lint 이후 변경된 페이지가 있어 되돌릴 수 없습니다: pageId="
                                + change.getResourceId());
            }
        }

        List<String> pageIds = changes.stream()
                .map(OperationChange::getResourceId)
                .distinct()
                .toList();
        Map<String, List<WikiPageContribution>> contributions = new LinkedHashMap<>();
        for (String pageId : pageIds) {
            contributions.put(pageId, new ArrayList<>());
        }
        for (WikiPageContribution contribution : contributionRepository.findByPageIds(pageIds)) {
            contributions.computeIfAbsent(contribution.getPageId(), key -> new ArrayList<>())
                    .add(contribution);
        }

        List<PageRestorePlan> pages = new ArrayList<>();
        for (OperationChange change : changes) {
            if (change.getChangeType() == ChangeType.created) {
                pages.add(PageRestorePlan.delete(change.getResourceId()));
                continue;
            }
            List<PageRestorePlan.Kept> kept = contributions.get(change.getResourceId()).stream()
                    .filter(WikiPageContribution::isActive)
                    .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                    .map(item -> new PageRestorePlan.Kept(
                            item.getIngestOperationId(), item.getSourceDocumentId(), item.getObjectKey()))
                    .toList();
            if (kept.isEmpty()) {
                throw new InvalidRestoreRequestException(
                        "lint 페이지를 재조립할 활성 기여가 없습니다: pageId="
                                + change.getResourceId());
            }
            pages.add(PageRestorePlan.rebuild(change.getResourceId(), kept));
        }
        return new Context(new RestorePlan(pages), contributions);
    }

    public record Context(
            RestorePlan plan,
            Map<String, List<WikiPageContribution>> contributions
    ) {}
}
