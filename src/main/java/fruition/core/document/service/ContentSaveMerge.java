package fruition.core.document.service;

import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import fruition.core.document.dto.DocumentContentSaveResponse.Merge;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.PostgresDocumentEditStore;

/**
 * 기준 버전이 지나간 본문 저장 한 건의 자동 병합(ADR 0032). 저장 transaction 한 번에 하나씩 만든다.
 *
 * <ul>
 *   <li>기준 버전 스냅샷(base)·현재 서버 본(theirs)·요청 본(mine)을 줄 단위로 3-way merge한다.</li>
 *   <li>겹치지 않으면 병합본을 저장한다(merged).</li>
 *   <li>겹치거나 기준 버전 스냅샷이 없으면 요청 본을 저장한다(overwritten, last-writer-wins).
 *       직전 서버 본은 저장 경로가 버전 이력에 남기므로 그 버전 번호를 알려 준다.</li>
 *   <li>기준 버전이 현재보다 크면 병합할 근거가 없어 충돌(409)로 남긴다.</li>
 * </ul>
 *
 * <p>{@link #mergeOnly}는 겹치거나 기준 버전 스냅샷이 없으면 덮어쓰지 않고 충돌(409)로 남긴다. 사용자가 보고 있지 않은
 * 문서에 승인된 Agent 편집을 적용할 때 쓴다(ADR 0032의 예외).
 */
final class ContentSaveMerge implements PostgresDocumentEditStore.StaleBaseResolver {

    private final DocumentContentVersionRepository versions;
    private final String documentId;
    private final long baseRevision;
    private final String mine;
    private final boolean rejectOverlap;

    private Merge outcome = Merge.NONE;
    private String mergedMarkdown;

    ContentSaveMerge(DocumentContentVersionRepository versions, String documentId, long baseRevision, String mine) {
        this(versions, documentId, baseRevision, mine, false);
    }

    private ContentSaveMerge(DocumentContentVersionRepository versions, String documentId, long baseRevision,
                             String mine, boolean rejectOverlap) {
        this.versions = versions;
        this.documentId = documentId;
        this.baseRevision = baseRevision;
        this.mine = mine;
        this.rejectOverlap = rejectOverlap;
    }

    static ContentSaveMerge mergeOnly(DocumentContentVersionRepository versions, String documentId, long baseRevision,
                                      String mine) {
        return new ContentSaveMerge(versions, documentId, baseRevision, mine, true);
    }

    @Override
    public PostgresDocumentEditStore.Resolution resolve(long currentRevision, String currentMarkdown) {
        if (baseRevision > currentRevision) {
            return null;
        }
        LineThreeWayMerge.Result result = versions.findById(new DocumentContentVersionId(documentId, baseRevision))
                .map(DocumentContentVersion::getMarkdown)
                .map(base -> LineThreeWayMerge.merge(base, currentMarkdown, mine))
                .orElseGet(() -> new LineThreeWayMerge.Result(mine, !mine.equals(currentMarkdown)));
        if (rejectOverlap && result.conflicted()) {
            return null;
        }
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.storedMarkdown(result.markdown());
        mergedMarkdown = content.markdown();
        outcome = result.conflicted()
                ? new Merge(Merge.STATUS_OVERWRITTEN, baseRevision, currentRevision, currentRevision)
                : new Merge(Merge.STATUS_MERGED, baseRevision, currentRevision, null);
        return new PostgresDocumentEditStore.Resolution(content.markdown(), content.contentHash());
    }

    Merge outcome() {
        return outcome;
    }

    /** 실제로 저장한 본문. 병합하지 않았으면 요청 본이다. */
    String savedMarkdown() {
        return mergedMarkdown != null ? mergedMarkdown : mine;
    }
}
