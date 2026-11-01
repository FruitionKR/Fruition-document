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

/**
 * 현재 llmPipeline 계약에 맞춰 lint 삭제·재조립 계획을 만든다.
 *
 * <p>대상 lint가 원장에 남긴 행(자기 기록·이은 편입 기록)은 남길 기여에서 빼고, 그 lint가 합쳐 지우며 끈
 * 기여는 다시 넣는다. 실제 활성 상태는 실행 때 {@link RestoreApplier}가 같은 규칙으로 바꾼다.
 */
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
        String lintId = target.getOperationId();
        // 페이지마다 이 lint의 마지막 변경 하나를 본다. 합쳐 지운 자식은 updated 뒤에 deleted가 온다.
        Map<String, List<OperationChange>> changesByPage = new LinkedHashMap<>();
        for (OperationChange change : operationChangeRepository.findByOperationIdOrderByIdAsc(lintId)) {
            if (change.getResourceType() == ResourceType.wiki_page
                    && (change.getChangeType() == ChangeType.created
                    || change.getChangeType() == ChangeType.updated
                    || change.getChangeType() == ChangeType.deleted)) {
                changesByPage.computeIfAbsent(change.getResourceId(), key -> new ArrayList<>()).add(change);
            }
        }
        if (changesByPage.isEmpty()) {
            return new Context(new RestorePlan(List.of()), Map.of());
        }

        for (List<OperationChange> pageChanges : changesByPage.values()) {
            OperationChange last = pageChanges.get(pageChanges.size() - 1);
            if (last.getId() == null || operationChangeRepository
                    .existsByResourceIdAndIdGreaterThan(last.getResourceId(), last.getId())) {
                throw new InvalidRestoreRequestException(
                        "대상 lint 이후 변경된 페이지가 있어 되돌릴 수 없습니다: pageId="
                                + last.getResourceId());
            }
        }

        List<String> pageIds = List.copyOf(changesByPage.keySet());
        Map<String, List<WikiPageContribution>> contributions = new LinkedHashMap<>();
        for (String pageId : pageIds) {
            contributions.put(pageId, new ArrayList<>());
        }
        for (WikiPageContribution contribution : contributionRepository.findByPageIds(pageIds)) {
            contributions.computeIfAbsent(contribution.getPageId(), key -> new ArrayList<>())
                    .add(contribution);
        }

        List<PageRestorePlan> pages = new ArrayList<>();
        for (Map.Entry<String, List<OperationChange>> entry : changesByPage.entrySet()) {
            String pageId = entry.getKey();
            if (entry.getValue().stream().anyMatch(change -> change.getChangeType() == ChangeType.created)) {
                pages.add(PageRestorePlan.delete(pageId));
                continue;
            }
            // 수정한 페이지는 다시 쓰고, 합쳐 지운 페이지는 이 lint가 끈 기여로 다시 만든다.
            List<WikiPageContribution> kept = contributions.get(pageId).stream()
                    .filter(item -> !lintId.equals(item.getIngestOperationId())
                            && !lintId.equals(item.getLintOperationId()))
                    .filter(item -> item.isActive() || lintId.equals(item.getDeactivatedBy()))
                    .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                    .toList();
            if (kept.stream().allMatch(WikiPageContribution::isLintRecord)) {
                throw new InvalidRestoreRequestException(
                        "lint 페이지를 재조립할 활성 기여가 없습니다: pageId=" + pageId);
            }
            pages.add(PageRestorePlan.rebuild(pageId, kept.stream()
                    .map(item -> new PageRestorePlan.Kept(
                            item.getIngestOperationId(), item.getSourceDocumentId(), item.getObjectKey()))
                    .toList()));
        }
        return new Context(new RestorePlan(pages), contributions);
    }

    public record Context(
            RestorePlan plan,
            Map<String, List<WikiPageContribution>> contributions
    ) {}
}
