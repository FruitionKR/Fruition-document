package fruition.core.wiki.service;

import fruition.core.aihistory.exception.WikiObjectReadException;
import fruition.core.aihistory.service.WikiObjectReader;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.dto.WikiGraphNode;
import fruition.core.wiki.dto.WikiGraphResponse;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.dto.WikiPageSourceDoc;
import fruition.core.wiki.exception.WikiPageNotFoundException;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock PipelineWikiStateRequester stateRequester;
    @Mock WikiPageVersionRepository versionRepository;
    @Mock MarkdownDiffService markdownDiffService;
    @Mock WikiObjectReader wikiObjectReader;
    @Mock WikiSourceTitleResolver sourceTitleResolver;
    WikiService service;

    @BeforeEach
    void setUp() {
        service = new WikiService(documentRepository, workspaceAccessGuard,
                stateRequester, versionRepository, markdownDiffService, wikiObjectReader, sourceTitleResolver);
    }

    @Test
    void graphDelegatesToAiState() {
        WikiGraphResponse graph = new WikiGraphResponse(List.of(
                new WikiGraphNode("wp_1", "concept", "제목", "title", "요약", "active", null)),
                List.of());
        when(stateRequester.graph("ws_1")).thenReturn(graph);

        assertThat(service.findGraph("ws_1", "user_1").nodes()).hasSize(1);
        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
    }

    @Test
    void missingPageIsNotFound() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("ws_1", "user_1", "wp_1"))
                .isInstanceOf(WikiPageNotFoundException.class);
    }

    @Test
    void detailExposesLatestRevisionAndKeepsAiMarkdown() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(page("# AI 본문", null)));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1"))
                .thenReturn(Optional.of(version(3, "# 버전 본문")));

        WikiPageDetailResponse detail = service.findById("ws_1", "user_1", "wp_1");

        assertThat(detail.revision()).isEqualTo(3L);
        assertThat(detail.markdown()).isEqualTo("# AI 본문");
        assertThat(detail.updatedAt()).isEqualTo(Instant.parse("2026-08-10T00:00:00Z"));
    }

    @Test
    void emptyMarkdownIsFilledFromLatestVersion() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(page("", "wiki/ws_1/pages/wp_1/ops/op_0.md")));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1"))
                .thenReturn(Optional.of(version(2, "# 버전 본문")));

        assertThat(service.findById("ws_1", "user_1", "wp_1").markdown()).isEqualTo("# 버전 본문");
        verifyNoInteractions(wikiObjectReader);
    }

    @Test
    void markdownStaysEmptyWithoutVersionOrObject() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(page(null, null)));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1")).thenReturn(Optional.empty());

        WikiPageDetailResponse detail = service.findById("ws_1", "user_1", "wp_1");

        assertThat(detail.markdown()).isNull();
        assertThat(detail.revision()).isNull();
    }

    @Test
    void blankMarkdownIsDroppedWhenNothingFillsIt() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(page("", null)));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1")).thenReturn(Optional.empty());

        assertThat(service.findById("ws_1", "user_1", "wp_1").markdown()).isNull();
    }

    @Test
    void detailSucceedsWithoutMarkdownWhenObjectReadFails() {
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(page("", "wiki/ws_1/pages/wp_1/ops/op_0.md")));
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("wp_1")).thenReturn(Optional.empty());
        when(wikiObjectReader.readPageObject("wiki/ws_1/pages/wp_1/ops/op_0.md", "ws_1", "wp_1"))
                .thenThrow(new WikiObjectReadException("본문 객체를 읽지 못했습니다: pageId=wp_1", null));

        WikiPageDetailResponse detail = service.findById("ws_1", "user_1", "wp_1");

        assertThat(detail.id()).isEqualTo("wp_1");
        assertThat(detail.markdown()).isNull();
    }

    @Test
    void graphReplacesSourceTitleButKeepsConceptAndMissingDocumentTitles() {
        Document doc = new Document("doc_1", "ws_1", "user_1", "새이름.pdf", "application/pdf", 1, "k", "h");
        WikiGraphResponse graph = new WikiGraphResponse(List.of(
                new WikiGraphNode("wp_c", "concept", "개념", "c", null, "active", null),
                new WikiGraphNode("wp_s", "source", "옛이름", "doc_1", null, "active",
                        new WikiGraphNode.SourceDocRef("doc_1", null)),
                new WikiGraphNode("wp_m", "source", "삭제된문서", "doc_2", null, "active",
                        new WikiGraphNode.SourceDocRef("doc_2", null))), List.of());
        when(stateRequester.graph("ws_1")).thenReturn(graph);
        when(documentRepository.findAllById(List.of("doc_1", "doc_2"))).thenReturn(List.of(doc));
        when(sourceTitleResolver.titles(eq("ws_1"), any())).thenReturn(Map.of("doc_1", "새이름 · 최상위"));

        List<WikiGraphNode> nodes = service.findGraph("ws_1", "user_1").nodes();

        assertThat(nodes).extracting(WikiGraphNode::title).containsExactly("개념", "새이름 · 최상위", "삭제된문서");
    }

    @Test
    void detailReplacesOnlySourcePageTitle() {
        Document doc = new Document("doc_1", "ws_1", "user_1", "새이름.pdf", "application/pdf", 1, "k", "h");
        WikiPageDetailResponse base = page("# 본문", null);
        WikiPageDetailResponse source = withType(base, "source", List.of(new WikiPageSourceDoc("doc_1", null, null, "source", 1)));
        WikiPageDetailResponse concept = withType(base, "concept", List.of(new WikiPageSourceDoc("doc_1", null, null, "support", 1)));
        when(documentRepository.findAllById(List.of("doc_1"))).thenReturn(List.of(doc));
        when(sourceTitleResolver.titles(eq("ws_1"), any())).thenReturn(Map.of("doc_1", "새이름"));

        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(source));
        assertThat(service.findById("ws_1", "user_1", "wp_1").title()).isEqualTo("새이름");

        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(concept));
        assertThat(service.findById("ws_1", "user_1", "wp_1").title()).isEqualTo("제목");

        when(sourceTitleResolver.titles(eq("ws_1"), any())).thenReturn(Map.of());
        when(stateRequester.page("ws_1", "wp_1")).thenReturn(Optional.of(source));
        assertThat(service.findById("ws_1", "user_1", "wp_1").title()).isEqualTo("제목");
    }

    private static WikiPageDetailResponse withType(WikiPageDetailResponse p, String type, List<WikiPageSourceDoc> docs) {
        return new WikiPageDetailResponse(p.id(), type, p.title(), p.slug(), p.summary(), p.markdownUri(), p.markdown(),
                p.status(), p.createdAt(), p.revision(), p.updatedAt(), docs, p.relatedPages());
    }

    static WikiPageDetailResponse page(String markdown, String markdownUri) {
        return new WikiPageDetailResponse("wp_1", "concept", "제목", "title", "요약", markdownUri, markdown,
                "active", Instant.parse("2026-08-01T00:00:00Z"), null, Instant.parse("2026-08-10T00:00:00Z"),
                List.of(), List.of());
    }

    private static WikiPageVersion version(long revision, String markdown) {
        return new WikiPageVersion("wp_1", revision, 1, markdown, "wiki/ws_1/pages/wp_1/ops/op_" + revision + ".md",
                "sha256:x", "op_" + revision, null, Instant.parse("2026-08-10T00:00:00Z"));
    }
}
