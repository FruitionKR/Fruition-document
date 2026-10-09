package fruition.core.wiki.service;

import fruition.core.aihistory.service.WikiObjectReader;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.dto.MarkdownDiff;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.domain.WikiPageVersionId;
import fruition.core.wiki.dto.WikiPageDiffResponse;
import fruition.core.wiki.exception.WikiPageVersionNotFoundException;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.dto.*;
import fruition.core.wiki.repository.PipelineWikiPageRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    private final DocumentRepository documentRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiPageRequester pipelineWikiPageRequester;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final MarkdownDiffService markdownDiffService;
    private final WikiObjectReader wikiObjectReader;

    public WikiService(DocumentRepository documentRepository,
                       WorkspaceAccessGuard workspaceAccessGuard,
                       PipelineWikiPageRequester pipelineWikiPageRequester,
                       PipelineWikiStateRequester pipelineWikiStateRequester,
                       WikiPageVersionRepository versionRepository,
                       MarkdownDiffService markdownDiffService,
                       WikiObjectReader wikiObjectReader) {
        this.documentRepository = documentRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.pipelineWikiPageRequester = pipelineWikiPageRequester;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.versionRepository = versionRepository;
        this.markdownDiffService = markdownDiffService;
        this.wikiObjectReader = wikiObjectReader;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    public WikiGraphResponse findGraph(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);

        WikiGraphResponse graph = pipelineWikiStateRequester.graph(workspaceId);
        List<String> documentIds = graph.nodes().stream()
                .map(WikiGraphNode::sourceDocument)
                .filter(java.util.Objects::nonNull)
                .map(WikiGraphNode.SourceDocRef::id)
                .distinct()
                .toList();
        Map<String, Document> docMap = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, d -> d));
        List<WikiGraphNode> nodes = graph.nodes().stream().map(node -> {
            var source = node.sourceDocument();
            Document document = source == null ? null : docMap.get(source.id());
            return new WikiGraphNode(
                    node.id(), node.pageType(), node.title(), node.slug(), node.summary(), node.status(),
                    source == null ? null : new WikiGraphNode.SourceDocRef(
                            source.id(), document == null ? null : document.getFilename()));
        }).toList();
        return new WikiGraphResponse(nodes, graph.edges());
    }

    public WikiPageDetailResponse findById(String workspaceId, String userId, String id) {
        verifyWorkspaceOwnership(workspaceId, userId);
        WikiPageDetailResponse page = pipelineWikiStateRequester.page(workspaceId, id)
                .orElseThrow(() -> new WikiPageNotFoundException(id));
        Map<String, Document> documents = documentRepository.findAllById(
                        page.sourceDocuments().stream().map(WikiPageSourceDoc::id).toList()).stream()
                .collect(Collectors.toMap(Document::getId, document -> document));
        List<WikiPageSourceDoc> sourceDocuments = page.sourceDocuments().stream()
                .map(source -> {
                    Document document = documents.get(source.id());
                    return new WikiPageSourceDoc(
                            source.id(),
                            document == null ? null : document.getFilename(),
                            document == null ? null : document.getSourceUri(),
                            source.relationType(),
                            source.confidence());
                })
                .toList();

        WikiPageVersion latest = versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(id).orElse(null);
        return new WikiPageDetailResponse(
                page.id(), page.pageType(), page.title(), page.slug(), page.summary(),
                page.markdownUri(), markdownOf(workspaceId, page, latest), page.status(), page.createdAt(),
                latest == null ? null : latest.getRevision(), page.updatedAt(),
                sourceDocuments,
                page.relatedPages());
    }

    /**
     * 화면이 {@code markdown_uri}를 읽지 않으므로 본문은 여기서 채운다.
     *
     * <p>AI가 준 본문을 먼저 쓴다. 비어 있으면 최신 {@code wiki_page_versions.markdown}을 쓴다.
     * 버전 행은 applier가 객체 본문의 hash를 확인한 뒤 남긴 것이라 객체와 같은 내용이고 저장소 호출이 없다.
     * 버전도 없으면(이력 도입 전 페이지) 버전의 {@code markdown_key}나 AI의 {@code markdown_uri} 객체를 읽는다.
     * 끝내 못 구하면 상세 조회 자체는 실패시키지 않고 빈 값으로 둔다.
     */
    private String markdownOf(String workspaceId, WikiPageDetailResponse page, WikiPageVersion latest) {
        if (page.markdown() != null && !page.markdown().isBlank()) {
            return page.markdown();
        }
        if (latest != null && !latest.getMarkdown().isBlank()) {
            return latest.getMarkdown();
        }
        String key = latest != null ? latest.getMarkdownKey() : page.markdownUri();
        if (key != null && !key.isBlank()) {
            try {
                return wikiObjectReader.readPageObject(key, workspaceId, page.id());
            } catch (RuntimeException e) {
                log.warn("[Wiki 본문 객체 읽기 실패] pageId={} key={}", page.id(), key, e);
            }
        }
        log.warn("[Wiki 본문 없음] pageId={}", page.id());
        return page.markdown();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WikiPageRenameResponse rename(String workspaceId, String userId, String wikiPageId,
                                         WikiPageRenameRequest request) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return pipelineWikiPageRequester.rename(workspaceId, userId, wikiPageId, request);
    }

    /**
     * 두 revision 사이의 변경분. 저장된 본문을 읽어 그 자리에서 계산한다.
     *
     * <p>diff 본문을 저장하지 않는 이유는 전체 본문이 바로 옆에 있어 언제든 다시 만들 수 있고,
     * 중복 저장하면 두 값이 어긋날 여지가 생기기 때문이다. 사용자가 펼칠 때만 호출된다.
     */
    @Transactional(readOnly = true)
    public WikiPageDiffResponse diff(String workspaceId, String userId, String pageId,
                                     long fromRevision, long toRevision) {
        verifyWorkspaceOwnership(workspaceId, userId);
        pipelineWikiStateRequester.page(workspaceId, pageId)
                .orElseThrow(() -> new WikiPageNotFoundException(pageId));
        WikiPageVersion before = loadVersion(pageId, fromRevision);
        WikiPageVersion after = loadVersion(pageId, toRevision);
        MarkdownDiff diff = markdownDiffService.diff(
                fromRevision, before.getMarkdown(), toRevision, after.getMarkdown());
        return WikiPageDiffResponse.from(pageId, diff);
    }

    private WikiPageVersion loadVersion(String pageId, long revision) {
        return versionRepository.findById(new WikiPageVersionId(pageId, revision))
                .orElseThrow(() -> new WikiPageVersionNotFoundException(pageId, revision));
    }
}
