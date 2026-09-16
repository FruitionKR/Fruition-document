package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import fruition.core.document.dto.DocumentContentDiffResponse;
import fruition.core.document.dto.MarkdownDiff;
import fruition.core.document.exception.MarkdownDiffTooLargeException;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 작업이 바꾼 리소스들의 실제 변경분을 계산한다. 저장된 본문을 읽어 조회 시점에 비교한다.
 *
 * <p>한 항목의 계산이 실패해도 상세 조회 전체를 실패시키지 않는다. 큰 페이지 하나 때문에
 * 나머지 멀쩡한 항목까지 못 보는 것은 잘못된 트레이드오프다.
 */
@Component
public class ChangeDiffLoader {

    private static final Logger log = LoggerFactory.getLogger(ChangeDiffLoader.class);

    private final WikiPageVersionRepository wikiVersionRepository;
    private final DocumentContentVersionRepository documentVersionRepository;
    private final MarkdownDiffService markdownDiffService;

    public ChangeDiffLoader(WikiPageVersionRepository wikiVersionRepository,
                            DocumentContentVersionRepository documentVersionRepository,
                            MarkdownDiffService markdownDiffService) {
        this.wikiVersionRepository = wikiVersionRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.markdownDiffService = markdownDiffService;
    }

    /**
     * 변경내역 전부의 변경분을 만든다.
     *
     * <p>필요한 본문을 <b>일괄 조회</b>한 뒤 비교한다. 항목마다 따로 읽으면 리소스 수만큼
     * 쿼리가 늘어난다(30개면 60번).
     *
     * @return 입력과 같은 순서의 결과
     */
    public List<Diff> load(List<OperationChange> changes) {
        Map<WikiPageVersionId, String> wikiMarkdown = loadWikiMarkdown(changes);
        Map<DocumentContentVersionId, String> documentMarkdown = loadDocumentMarkdown(changes);

        List<Diff> diffs = new ArrayList<>(changes.size());
        for (OperationChange change : changes) {
            diffs.add(diff(change, wikiMarkdown, documentMarkdown));
        }
        return diffs;
    }

    private Diff diff(OperationChange change,
                      Map<WikiPageVersionId, String> wikiMarkdown,
                      Map<DocumentContentVersionId, String> documentMarkdown) {
        if (!diffable(change)) {
            return Diff.none();
        }
        long before = change.getBeforeRevision() == null ? 0L : change.getBeforeRevision();
        long after = change.getAfterRevision();

        String beforeMarkdown = change.getChangeType() == ChangeType.created
                ? ""
                : markdown(change, before, wikiMarkdown, documentMarkdown);
        String afterMarkdown = markdown(change, after, wikiMarkdown, documentMarkdown);
        if (beforeMarkdown == null || afterMarkdown == null) {
            return Diff.none();
        }

        try {
            MarkdownDiff diff = markdownDiffService.diff(before, beforeMarkdown, after, afterMarkdown);
            return new Diff(diff.hunks(), false);
        } catch (MarkdownDiffTooLargeException e) {
            return new Diff(null, true);
        } catch (RuntimeException e) {
            // 크기 초과가 아닌 실패를 too_large로 보고하면 오진을 부른다. 변경분만 비운다.
            log.warn("[변경분 계산 생략] resourceId={} {}→{} reason={}",
                    change.getResourceId(), before, after, e.getMessage());
            return Diff.none();
        }
    }

    private String markdown(OperationChange change, long revision,
                            Map<WikiPageVersionId, String> wikiMarkdown,
                            Map<DocumentContentVersionId, String> documentMarkdown) {
        return change.getResourceType() == ResourceType.wiki_page
                ? wikiMarkdown.get(new WikiPageVersionId(change.getResourceId(), revision))
                : documentMarkdown.get(new DocumentContentVersionId(change.getResourceId(), revision));
    }

    private Map<WikiPageVersionId, String> loadWikiMarkdown(List<OperationChange> changes) {
        List<WikiPageVersionId> ids = new ArrayList<>();
        for (OperationChange change : changes) {
            if (diffable(change) && change.getResourceType() == ResourceType.wiki_page) {
                if (change.getBeforeRevision() != null) {
                    ids.add(new WikiPageVersionId(change.getResourceId(), change.getBeforeRevision()));
                }
                ids.add(new WikiPageVersionId(change.getResourceId(), change.getAfterRevision()));
            }
        }
        Map<WikiPageVersionId, String> markdown = new HashMap<>();
        if (!ids.isEmpty()) {
            for (WikiPageVersion version : wikiVersionRepository.findAllById(ids)) {
                markdown.put(new WikiPageVersionId(version.getPageId(), version.getRevision()),
                        version.getMarkdown());
            }
        }
        return markdown;
    }

    private Map<DocumentContentVersionId, String> loadDocumentMarkdown(List<OperationChange> changes) {
        List<DocumentContentVersionId> ids = new ArrayList<>();
        for (OperationChange change : changes) {
            if (diffable(change) && change.getResourceType() == ResourceType.document) {
                if (change.getBeforeRevision() != null) {
                    ids.add(new DocumentContentVersionId(change.getResourceId(), change.getBeforeRevision()));
                }
                ids.add(new DocumentContentVersionId(change.getResourceId(), change.getAfterRevision()));
            }
        }
        Map<DocumentContentVersionId, String> markdown = new HashMap<>();
        if (!ids.isEmpty()) {
            for (DocumentContentVersion version : documentVersionRepository.findAllById(ids)) {
                markdown.put(new DocumentContentVersionId(version.getDocumentId(), version.getVersion()),
                        version.getMarkdown());
            }
        }
        return markdown;
    }

    /** 생성은 빈 본문과 비교하고, 그 외 변경은 전후 revision이 모두 있을 때 비교한다. */
    private boolean diffable(OperationChange change) {
        return change.getAfterRevision() != null
                && (change.getBeforeRevision() != null || change.getChangeType() == ChangeType.created);
    }

    /**
     * @param tooLarge 두 본문 차이가 너무 커서 계산을 거부한 경우. 개별 diff 엔드포인트로도 볼 수 없다
     */
    public record Diff(List<DocumentContentDiffResponse.Hunk> hunks, boolean tooLarge) {
        private static final Diff NONE = new Diff(null, false);

        public static Diff none() {
            return NONE;
        }
    }
}
