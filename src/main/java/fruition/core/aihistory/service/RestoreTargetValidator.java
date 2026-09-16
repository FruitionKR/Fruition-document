package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 되돌릴 수 있는 대상인지 확인한다.
 *
 * <p>미리보기와 실행이 <b>같은 규칙</b>을 써야 한다. 미리보기가 통과시킨 것을 실행이 거절하면
 * 사용자가 확인 화면을 다 보고 나서 실패한다.
 */
@Component
public class RestoreTargetValidator {

    private final PipelineWikiStateRequester wikiStateRequester;

    public RestoreTargetValidator(PipelineWikiStateRequester wikiStateRequester) {
        this.wikiStateRequester = wikiStateRequester;
    }

    /**
     * 되돌릴 수 있는 작업 유형인지. {@code restore}를 되돌리는 것은 지원하지 않는다.
     * 되돌리기를 무르려면 그 결과를 다시 되돌리는 것이 아니라 원하는 시점을 새로 지목해야 한다.
     */
    public void requireRestorable(OperationLog target) {
        if (target.isDocumentRestoreBlocked()) {
            throw new InvalidRestoreRequestException(
                    "fresh cutover 이전 문서 편집 작업은 복구할 수 없습니다.");
        }
        OperationType type = target.getOperationType();
        if (type != OperationType.document_edit && type != OperationType.ingest
                && type != OperationType.lint) {
            throw new InvalidRestoreRequestException("되돌릴 수 없는 작업입니다: " + type);
        }
    }

    /**
     * Wiki 계획을 실행할 수 있는지 확인한다.
     *
     * @return ingest면 원문을 대표하는 source page. lint는 그런 페이지가 없어 {@code null}
     */
    public PageRestorePlan requireApplicable(OperationLog target, RestorePlan plan) {
        if (plan.pages().isEmpty()) {
            throw new InvalidRestoreRequestException("되돌릴 Wiki 페이지가 없습니다.");
        }
        if (target.getOperationType() != OperationType.ingest) {
            return null;
        }
        return requireSourcePage(plan, target.getWorkspaceId());
    }

    /**
     * 계획에 든 페이지 중 원문을 대표하는 source page. ingest는 항상 하나를 건드린다.
     *
     * <p>llmPipeline의 {@code source_page}는 필수 필드라 없이 보내면 400으로 거절당한다. 그때는
     * 이미 DB 반영이 끝나 되돌릴 수 없으므로 반영 전에 확인한다.
     *
     * <p>{@code document_wiki_links}가 아니라 {@code wiki_pages.page_type}으로 찾는다. 링크
     * 테이블은 llmPipeline이 관리하고 문서 재처리 과정에서 지워질 수 있어, 페이지 자신이 들고
     * 있는 값을 보는 편이 안전하다.
     */
    private PageRestorePlan requireSourcePage(RestorePlan plan, String workspaceId) {
        List<String> pageIds = plan.pages().stream().map(PageRestorePlan::pageId).toList();
        Set<String> sourcePageIds = wikiStateRequester.lookup(pageIds, workspaceId).stream()
                .filter(page -> "source".equals(page.pageType()))
                .map(PipelineWikiStateRequester.WikiPageSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
        return plan.pages().stream()
                .filter(page -> sourcePageIds.contains(page.pageId()))
                .findFirst()
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "되돌릴 대상에 원문 페이지가 없습니다. 이미 되돌려졌는지 확인해 주세요."));
    }
}
