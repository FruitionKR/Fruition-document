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

/** Source는 마지막 남은 ingest 본문으로 복원하고 Concept는 남은 활성 기여로 재작성한다. */
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
        for (WikiPageContribution c : ordered) {
            if (!c.isActive()) {
                continue;  // 이전 복구로 이미 걷어낸 기여
            }
            if (excluded.contains(c.getIngestOperationId())) {
                removed.add(c);
            } else {
                kept.add(c);
            }
        }

        // 제외 대상이 이 페이지를 건드린 적이 없으면 복구 후보가 아니다.
        if (removed.isEmpty()) {
            return Optional.empty();
        }

        // 받치는 기여가 하나도 남지 않으면 페이지가 존재할 이유가 사라진다.
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
