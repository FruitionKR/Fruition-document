package fruition.core.wiki.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WikiService {

    private final DocumentRepository documentRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiPageRequester pipelineWikiPageRequester;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final MarkdownDiffService markdownDiffService;

    public WikiService(DocumentRepository documentRepository,
                       WorkspaceAccessGuard workspaceAccessGuard,
                       PipelineWikiPageRequester pipelineWikiPageRequester,
                       PipelineWikiStateRequester pipelineWikiStateRequester,
                       WikiPageVersionRepository versionRepository,
                       MarkdownDiffService markdownDiffService) {
        this.documentRepository = documentRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.pipelineWikiPageRequester = pipelineWikiPageRequester;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.versionRepository = versionRepository;
        this.markdownDiffService = markdownDiffService;
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

        return new WikiPageDetailResponse(
                page.id(), page.pageType(), page.title(), page.slug(), page.summary(),
                page.markdownUri(), page.markdown(), page.status(), page.createdAt(), page.updatedAt(),
                sourceDocuments,
                page.relatedPages());
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
