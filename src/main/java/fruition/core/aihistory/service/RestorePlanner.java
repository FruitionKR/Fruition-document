package fruition.core.aihistory.service;

import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.wiki.domain.WikiPageContribution;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Source는 마지막 남은 ingest 본문으로 복원하고 Concept는 남은 활성 기여로 재작성한다.
 *
 * <p>남은 기여에는 lint 행이 순서대로 들어 재조립이 lint 변경을 재생한다. lint가 제외 대상 작업 id로
 * 다른 페이지(분리한 자식)에 이은 행도 그 작업 id라 함께 빠진다.
 */
@Component
public class RestorePlanner {

    private final PipelineWikiStateRequester wikiStateRequester;

    public RestorePlanner(PipelineWikiStateRequester wikiStateRequester) {
        this.wikiStateRequester = wikiStateRequester;
    }

    /**
     * @param excludedOperationIds 취소할 ingest 작업 집합
     * @param contributionsByPage  페이지별 <b>전체</b> 기여(활성·비활성 모두)
     */
    public RestorePlan plan(Set<String> excludedOperationIds,
                            Map<String, List<WikiPageContribution>> contributionsByPage,
                            String workspaceId) {
        Set<String> sourcePageIds = contributionsByPage.isEmpty() ? Set.of()
                : wikiStateRequester.lookup(List.copyOf(contributionsByPage.keySet()), workspaceId).stream()
                        .filter(page -> "source".equals(page.pageType()))
                        .map(PipelineWikiStateRequester.WikiPageSnapshot::id)
                        .collect(java.util.stream.Collectors.toSet());
        List<PageRestorePlan> pages = new ArrayList<>();
        for (Map.Entry<String, List<WikiPageContribution>> entry : contributionsByPage.entrySet()) {
            planPage(entry.getKey(), entry.getValue(), excludedOperationIds,
                    sourcePageIds.contains(entry.getKey())).ifPresent(pages::add);
        }
        return new RestorePlan(pages);
    }

    private Optional<PageRestorePlan> planPage(String pageId,
                                               List<WikiPageContribution> contributions,
                                               Set<String> excluded, boolean sourcePage) {
        List<WikiPageContribution> ordered = contributions.stream()
                .sorted(Comparator.comparingLong(WikiPageContribution::getSequenceRevision))
                .toList();

        List<WikiPageContribution> kept = new ArrayList<>();
        List<WikiPageContribution> removed = new ArrayList<>();
        boolean revived = false;
        for (WikiPageContribution c : ordered) {
            if (!c.isActive()) {
                // 제외 대상 재편입이 대체해 끈 이전 편입 기여는 되살린다. 그 밖은 이전 복구로 걷어낸 기여다.
                if (c.getDeactivatedBy() != null && excluded.contains(c.getDeactivatedBy())
                        && !excluded.contains(c.getIngestOperationId())) {
                    kept.add(c);
                    revived = true;
                }
                continue;
            }
            if (excluded.contains(c.getIngestOperationId())) {
                removed.add(c);
            } else {
                kept.add(c);
            }
        }

        // 제외 대상이 이 페이지를 건드린 적이 없으면 복구 후보가 아니다.
        if (removed.isEmpty() && !revived) {
            return Optional.empty();
        }

        // 남은 활성 행이 하나도 없을 때만 지운다. lint 기록만 남은 페이지를 지울지는 ai 재생이 정한다
        // (클러스터 승격 페이지는 lint 기록이 근거다, ai ADR 0042 결정 3).
        if (kept.isEmpty()) {
            return Optional.of(PageRestorePlan.delete(pageId));
        }

        if (sourcePage) {
            WikiPageContribution lastKept = kept.get(kept.size() - 1);
            return Optional.of(PageRestorePlan.restore(
                    pageId, lastKept.getSequenceRevision(), lastKept.getIngestOperationId(), kept.size()));
        }

        // Concept는 과거 revision 유무와 관계없이 pipeline의 기여 재작성 계약을 따른다.
        List<PageRestorePlan.Kept> keepContributions = kept.stream()
                .map(c -> new PageRestorePlan.Kept(
                        c.getIngestOperationId(), c.getSourceDocumentId(), c.getObjectKey()))
                .toList();
        return Optional.of(PageRestorePlan.rebuild(pageId, keepContributions));
    }

}
