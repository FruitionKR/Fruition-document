package fruition.core.wiki.service;

import fruition.core.aihistory.exception.WikiObjectReadException;
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
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 위키 조회. 클래스 트랜잭션을 두지 않는다. 메서드마다 ai 호출(그래프·페이지)과 객체 저장소 읽기가 끼어 있어, 트랜잭션이
 * 있으면 그동안 DB 연결을 쥔다. DB 읽기는 저장소 호출마다 짧게 끝나며 지연 로딩 연관도 없다.
 */
@Service
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    private final DocumentRepository documentRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiStateRequester pipelineWikiStateRequester;
    private final WikiPageVersionRepository versionRepository;
    private final MarkdownDiffService markdownDiffService;
    private final WikiObjectReader wikiObjectReader;
    private final WikiWorkspaceVersionRepository wikiVersions;

    public WikiService(DocumentRepository documentRepository,
                       WorkspaceAccessGuard workspaceAccessGuard,
                       PipelineWikiStateRequester pipelineWikiStateRequester,
                       WikiPageVersionRepository versionRepository,
                       MarkdownDiffService markdownDiffService,
                       WikiObjectReader wikiObjectReader,
                       WikiWorkspaceVersionRepository wikiVersions) {
        this.documentRepository = documentRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.pipelineWikiStateRequester = pipelineWikiStateRequester;
        this.versionRepository = versionRepository;
        this.markdownDiffService = markdownDiffService;
        this.wikiObjectReader = wikiObjectReader;
        this.wikiVersions = wikiVersions;
    }

    private void verifyWorkspaceOwnership(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    /**
     * 그래프 ETag의 기준인 워크스페이스 위키 버전(#68). 멤버가 아니면 404다.
     * 호출자는 이 값을 그래프보다 <b>먼저</b> 읽어야 한다. ai는 위키를 바꾼 뒤 결과를 보내므로,
     * 먼저 읽은 버전에 대응하는 그래프는 그보다 오래될 수 없다.
     */
    public long graphVersion(String workspaceId, String userId) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return wikiVersions.current(workspaceId);
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
        return read(workspaceId, userId, id).detail();
    }

    /**
     * 웹 상세 조회 결과와, 그 조회가 고른 본문. {@code body}는 빈 본문이면 빈 문자열이고 끝내 못 구했으면 비어 있다.
     * 웹 응답({@code detail.markdown})은 둘 다 null로 둔다. MCP {@code get_wiki_page}는 빈 본문을 정상 응답으로 돌려준다.
     */
    /** objectMarkdown은 AI가 markdown_uri 객체에서 읽은 원문 그대로다(없으면 null). MCP lint 대상 hash의 기준이다. */
    public record PageRead(WikiPageDetailResponse detail, Optional<String> body, String objectMarkdown) {}

    /** 지금 페이지 metadata와 최신 revision. 없으면 비어 있다. MCP get_wiki_page가 읽은 뒤 변경 여부를 볼 때 쓴다. */
    public record PageState(String pageType, String title, String markdownUri, String status,
                            java.time.Instant updatedAt, Long revision) {}

    public Optional<PageState> currentState(String workspaceId, String userId, String id) {
        verifyWorkspaceOwnership(workspaceId, userId);
        Long revision = versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(id)
                .map(WikiPageVersion::getRevision).orElse(null);
        return pipelineWikiStateRequester.state(workspaceId, id).map(state -> new PageState(
                state.pageType(), state.title(), state.markdownUri(), state.status(), state.updatedAt(), revision));
    }

    public PageRead read(String workspaceId, String userId, String id) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return read(workspaceId, id, pipelineWikiStateRequester.page(workspaceId, id));
    }

    /** MCP get_wiki_page용. pipeline에 lint 준비 정보를 함께 요청한다. */
    public PageRead readForMcp(String workspaceId, String userId, String id) {
        verifyWorkspaceOwnership(workspaceId, userId);
        return read(workspaceId, id, pipelineWikiStateRequester.page(workspaceId, id, true));
    }

    private PageRead read(String workspaceId, String id, Optional<WikiPageDetailResponse> found) {
        WikiPageDetailResponse page = found.orElseThrow(() -> new WikiPageNotFoundException(id));
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
        Optional<String> body = markdownOf(workspaceId, page, latest);
        return new PageRead(new WikiPageDetailResponse(
                page.id(), page.pageType(), page.title(), page.slug(), page.summary(),
                page.markdownUri(), body.filter(markdown -> !markdown.isBlank()).orElse(null), page.status(),
                page.createdAt(), latest == null ? null : latest.getRevision(), page.updatedAt(),
                sourceDocuments,
                page.relatedPages(), page.statements(), page.links(), page.lintTargetPageIds(),
                page.lintTargetTruncated(), page.lintContextError()), body, page.markdown());
    }

    /**
     * 화면이 {@code markdown_uri}를 읽지 않으므로 본문은 여기서 채운다.
     *
     * <p>AI가 준 본문을 먼저 쓴다. 비어 있으면 최신 {@code wiki_page_versions.markdown}을 쓴다.
     * 버전 행은 applier가 객체 본문의 hash를 확인한 뒤 남긴 것이라 객체와 같은 내용이고 저장소 호출이 없다.
     * 버전도 없으면(이력 도입 전 페이지) 버전의 {@code markdown_key}나 AI의 {@code markdown_uri} 객체를 읽는다.
     * 끝내 못 구하면 상세 조회 자체는 실패시키지 않고 비워 둔다. 저장 객체를 읽었는데 비어 있으면 빈 본문이다.
     */
    private Optional<String> markdownOf(String workspaceId, WikiPageDetailResponse page, WikiPageVersion latest) {
        if (page.markdown() != null && !page.markdown().isBlank()) {
            return Optional.of(page.markdown());
        }
        if (latest != null && !latest.getMarkdown().isBlank()) {
            return Optional.of(latest.getMarkdown());
        }
        String key = latest != null ? latest.getMarkdownKey() : page.markdownUri();
        if (key != null && !key.isBlank()) {
            try {
                String markdown = wikiObjectReader.readPageObject(key, workspaceId, page.id());
                if (!markdown.isBlank()) {
                    return Optional.of(markdown);
                }
                log.warn("[Wiki 본문 없음] pageId={}", page.id());
                return Optional.of(markdown);
            } catch (WikiObjectReadException e) {
                // 저장소 실패는 reader가 이미 남겼다.
                return Optional.empty();
            } catch (RuntimeException e) {
                log.warn("[Wiki 본문 객체 읽기 실패] pageId={} key={} reason={}", page.id(), key, e.getMessage());
                return Optional.empty();
            }
        }
        log.warn("[Wiki 본문 없음] pageId={}", page.id());
        return Optional.empty();
    }

    /**
     * 두 revision 사이의 변경분. 저장된 본문을 읽어 그 자리에서 계산한다.
     *
     * <p>diff 본문을 저장하지 않는 이유는 전체 본문이 바로 옆에 있어 언제든 다시 만들 수 있고,
     * 중복 저장하면 두 값이 어긋날 여지가 생기기 때문이다. 사용자가 펼칠 때만 호출된다.
     */
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
