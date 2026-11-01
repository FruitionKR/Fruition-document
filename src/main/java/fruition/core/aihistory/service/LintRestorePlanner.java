package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 현재 llmPipeline 계약에 맞춰 lint 삭제·재조립 계획을 만든다.
 *
 * <p>되돌리기는 대상 lint의 효과만 뺀다(ai ADR 0042). 대상 lint가 원장에 남긴 행(자기 기록·이은 편입 기록)을
 * 남길 기여에서 빼고 나머지를 재생한다. 실제 활성 상태는 실행 때 {@link RestoreApplier}가 같은 규칙으로 바꾼다.
 */
@Component
public class LintRestorePlanner {

    private static final Set<OperationStatus> RESTORED = Set.of(
            OperationStatus.succeeded, OperationStatus.partially_succeeded);

    private final OperationChangeRepository operationChangeRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final OperationLogRepository operationLogRepository;

    public LintRestorePlanner(OperationChangeRepository operationChangeRepository,
                              WikiPageContributionRepository contributionRepository,
                              OperationLogRepository operationLogRepository) {
        this.operationChangeRepository = operationChangeRepository;
        this.contributionRepository = contributionRepository;
        this.operationLogRepository = operationLogRepository;
    }

    public Context plan(OperationLog target) {
        String lintId = target.getOperationId();
        // 페이지마다 이 lint의 마지막 변경을 본다. 합쳐 지운 자식은 updated 뒤에 deleted가 온다.
        Map<String, OperationChange> lastChanges = new LinkedHashMap<>();
        for (OperationChange change : operationChangeRepository.findByOperationIdOrderByIdAsc(lintId)) {
            if (change.getResourceType() == ResourceType.wiki_page
                    && (change.getChangeType() == ChangeType.created
                    || change.getChangeType() == ChangeType.updated
                    || change.getChangeType() == ChangeType.deleted)) {
                lastChanges.put(change.getResourceId(), change);
            }
        }
        if (lastChanges.isEmpty()) {
            return new Context(new RestorePlan(List.of()), Map.of());
        }

        // 뒤에 다른 lint가 같은 페이지를 바꿨으면 그 구조 변경이 대상 lint에 기대고 있을 수 있다. 쓰기 전에
        // 거절하고 뒤 lint부터 되돌리게 한다. 뒤 편입·사람 수정은 독립 기여라 재조립이 그대로 재생한다.
        for (OperationChange last : lastChanges.values()) {
            if (last.getId() == null || operationChangeRepository
                    .findByResourceIdAndIdGreaterThan(last.getResourceId(), last.getId()).stream()
                    .map(OperationChange::getOperationId)
                    .distinct()
                    .anyMatch(this::isUnrestoredLint)) {
                throw new InvalidRestoreRequestException(
                        "대상 lint 이후 변경된 페이지가 있어 되돌릴 수 없습니다. 뒤 lint부터 되돌려 주세요: pageId="
                                + last.getResourceId());
            }
        }

        List<String> pageIds = List.copyOf(lastChanges.keySet());
        Map<String, List<WikiPageContribution>> contributions = new LinkedHashMap<>();
        for (String pageId : pageIds) {
            contributions.put(pageId, new ArrayList<>());
        }
        for (WikiPageContribution contribution : contributionRepository.findByPageIds(pageIds)) {
            contributions.computeIfAbsent(contribution.getPageId(), key -> new ArrayList<>())
                    .add(contribution);
        }

        // 대상 lint의 행(자기 기록·이은 기록)만 빼고 남은 활성 기여로 다시 쓴다. 대상이 만든 페이지라도 뒤
        // 기여가 남으면 재조립에 맡기고, 남은 활성 행이 없을 때만 지운다. lint 기록만 남은 페이지를 지울지는
        // ai 재생이 정해 결과의 deleted_pages로 알린다.
        List<PageRestorePlan> pages = new ArrayList<>();
        for (String pageId : pageIds) {
            List<WikiPageContribution> kept = contributions.get(pageId).stream()
                    .filter(WikiPageContribution::isActive)
                    .filter(item -> !lintId.equals(item.getIngestOperationId())
                            && !lintId.equals(item.getLintOperationId()))
                    .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                    .toList();
            if (kept.isEmpty()) {
                pages.add(PageRestorePlan.delete(pageId));
                continue;
            }
            pages.add(PageRestorePlan.rebuild(pageId, kept.stream()
                    .map(item -> new PageRestorePlan.Kept(
                            item.getIngestOperationId(), item.getSourceDocumentId(), item.getObjectKey()))
                    .toList()));
        }
        return new Context(new RestorePlan(pages), contributions);
    }

    private boolean isUnrestoredLint(String operationId) {
        return operationLogRepository.findById(operationId)
                .filter(operation -> operation.getOperationType() == OperationType.lint)
                .isPresent()
                && !operationLogRepository.existsByRestoredFromAndStatusIn(operationId, RESTORED);
    }

    public record Context(
            RestorePlan plan,
            Map<String, List<WikiPageContribution>> contributions
    ) {}
}
