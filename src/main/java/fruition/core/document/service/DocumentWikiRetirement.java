package fruition.core.document.service;

import fruition.core.document.repository.IngestCommandOutbox;
import fruition.core.wiki.repository.WikiPageContributionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 휴지통으로 옮긴 문서를 위키에서 뺀다.
 *
 * <p>위키 페이지는 AI가 소유하므로 정리 command를 보낸다. 로그 되돌리기는 document-svc의 활성 기여로
 * 복구 대상을 정하므로, 이 문서의 기여도 같은 트랜잭션에서 끈다. 끄지 않으면 되돌리기가 삭제한 문서의
 * source 페이지와 개념 기여를 다시 살린다.
 */
@Component
public class DocumentWikiRetirement {

    private final IngestCommandOutbox ingestCommandOutbox;
    private final WikiPageContributionRepository contributionRepository;

    public DocumentWikiRetirement(IngestCommandOutbox ingestCommandOutbox,
                                  WikiPageContributionRepository contributionRepository) {
        this.ingestCommandOutbox = ingestCommandOutbox;
        this.contributionRepository = contributionRepository;
    }

    public void retire(String workspaceId, List<String> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        contributionRepository.deactivateBySourceDocumentIds(documentIds);
        documentIds.forEach(documentId -> ingestCommandOutbox.enqueueDelete(documentId, workspaceId));
    }
}
