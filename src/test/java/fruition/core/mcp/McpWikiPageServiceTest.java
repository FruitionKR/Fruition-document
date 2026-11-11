package fruition.core.mcp;

import fruition.core.aihistory.service.WikiObjectReader;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.MarkdownDiffService;
import fruition.core.wiki.domain.WikiPageVersion;
import fruition.core.wiki.dto.WikiPageDetailResponse;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wiki.repository.WikiPageVersionRepository;
import fruition.core.wiki.repository.WikiWorkspaceVersionRepository;
import fruition.core.wiki.service.WikiService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** get_wiki_page는 웹 상세 조회(WikiService.findById)가 고른 본문을 그대로 쓰고, MCP 경계 처리만 더한다. */
class McpWikiPageServiceTest {
    private final PipelineWikiStateRequester ai = mock(PipelineWikiStateRequester.class);
    private final WikiPageVersionRepository versions = mock(WikiPageVersionRepository.class);
    private final WikiObjectReader objects = mock(WikiObjectReader.class);
    private final WorkspaceAccessGuard guard = mock(WorkspaceAccessGuard.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final WikiService web = new WikiService(documents, guard, ai, versions,
            mock(MarkdownDiffService.class), objects, mock(WikiWorkspaceVersionRepository.class));
    private static final Instant UPDATED = Instant.parse("2026-08-10T00:00:00Z");

    @org.junit.jupiter.api.BeforeEach void sameStateAfterRead() {
        // 기본값: 읽은 뒤에도 페이지가 그대로다.
        when(ai.state(any(), any())).thenAnswer(call -> ai.page(call.getArgument(0), call.getArgument(1))
                .map(p -> new PipelineWikiStateRequester.PageState(
                        p.id(), p.pageType(), p.title(), p.markdownUri(), p.status(), p.updatedAt())));
    }

    @Test void readsTheBodyTheWebShowsWhenTheAiObjectIsMissing() throws Exception {
        // AI가 markdown_uri 객체를 못 읽어도 웹은 최신 버전 행 본문을 보여 준다. MCP도 같은 본문이어야 한다.
        when(ai.page("workspace-a", "page-a")).thenReturn(Optional.of(page(null, "wiki/missing.md")));
        when(versions.findTopByIdPageIdOrderByIdRevisionDesc("page-a"))
                .thenReturn(Optional.of(new WikiPageVersion("page-a", 2, 1, "# 버전 본문  \n", "k2", "h2", null, null, null)));

        var page = service(10_000).get(identity(), "page-a");

        assertThat(page.body()).isEqualTo("# 버전 본문  \n");
        assertThat(page.contentHash()).isEqualTo("sha256:" + sha256("# 버전 본문  \n"));
        assertThat(page.pageType()).isEqualTo("SOURCE");
        assertThat(page.schemaVersion()).isEqualTo("mcp.wiki-page.v1");
        assertThat(page.dataBoundary()).isEqualTo("UNTRUSTED_WORKSPACE_CONTENT");
        verify(guard).requireMember("workspace-a", "user-a");
        verifyNoInteractions(objects);
    }

    @Test void neutralizesTitleAndBodyAndHashesTheReturnedBody() {
        String rawBody = "![alt](https://evil.example/image.png)\n[Read](https://evil.example/path)";
        String sanitized = "외부 이미지(evil.example)\nRead (evil.example)";
        when(ai.page("workspace-a", "page-a")).thenReturn(Optional.of(
                new WikiPageDetailResponse("page-a", "concept", "[Title](https://evil.example/t)", null, null,
                        null, rawBody, "active", null, null, null, List.of(), List.of())));

        var page = service(10_000).get(identity(), "page-a");

        assertThat(page.title()).isEqualTo("Title (evil.example)");
        assertThat(page.body()).isEqualTo(sanitized);
        assertThat(page.pageType()).isEqualTo("CONCEPT");
        assertThat(page.contentHash()).isEqualTo("sha256:" + sha256(sanitized));
    }

    @Test void mapsWebFailuresToMcpCodes() {
        when(ai.page("workspace-a", "missing")).thenReturn(Optional.empty());
        when(ai.page("workspace-a", "no-body")).thenReturn(Optional.of(page(null, null)));
        when(ai.page("workspace-a", "page-a")).thenReturn(Optional.of(page("한글", null)));
        var service = service(5);

        assertThatThrownBy(() -> service.get(identity(), "missing")).hasMessage("PAGE_NOT_FOUND");
        assertThatThrownBy(() -> service.get(identity(), "no-body")).hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThatThrownBy(() -> service.get(identity(), "page-a")).hasMessage("RESPONSE_LIMIT_EXCEEDED");
        assertThatThrownBy(() -> service.get(identity(), "../internal")).hasMessage("INVALID_INPUT");
        doThrow(new WorkspaceNotFoundException("ws")).when(guard).requireMember(any(), any());
        assertThatThrownBy(() -> service.get(identity(), "page-a")).hasMessage("PAGE_NOT_FOUND");
    }

    @Test void returnsAnEmptyPublishedBodyAsANormalResponse() throws Exception {
        String key = "wiki/workspace-a/pages/page-a/v.md";
        when(ai.page("workspace-a", "page-a")).thenReturn(Optional.of(page("", key)));
        when(objects.readPageObject(key, "workspace-a", "page-a")).thenReturn("");

        var page = service(10_000).get(identity(), "page-a");

        assertThat(page.body()).isEmpty();
        assertThat(page.contentHash()).isEqualTo("sha256:" + sha256(""));
    }

    @Test void rejectsAPageThatChangedDuringTheRead() {
        when(ai.page("workspace-a", "page-a")).thenReturn(Optional.of(page("# body", "wiki/workspace-a/pages/page-a/v.md")));
        doReturn(Optional.of(new PipelineWikiStateRequester.PageState(
                "page-a", "source", "Title", "wiki/workspace-a/pages/page-a/v.md", "active", UPDATED.plusSeconds(1))))
                .when(ai).state("workspace-a", "page-a");
        assertThatThrownBy(() -> service(10_000).get(identity(), "page-a")).hasMessage("PAGE_CHANGED");

        doReturn(Optional.empty()).when(ai).state("workspace-a", "page-a");
        assertThatThrownBy(() -> service(10_000).get(identity(), "page-a")).hasMessage("PAGE_CHANGED");
    }

    @Test void rejectsANewRevisionWrittenDuringTheRead() {
        when(ai.page("workspace-a", "page-a")).thenReturn(Optional.of(page(null, "wiki/workspace-a/pages/page-a/v.md")));
        when(versions.findTopByIdPageIdOrderByIdRevisionDesc("page-a"))
                .thenReturn(Optional.of(new WikiPageVersion("page-a", 2, 1, "# v2", "k2", "h2", null, null, null)))
                .thenReturn(Optional.of(new WikiPageVersion("page-a", 3, 1, "# v3", "k3", "h3", null, null, null)));

        assertThatThrownBy(() -> service(10_000).get(identity(), "page-a")).hasMessage("PAGE_CHANGED");
    }

    @Test void enforcesTheReadDeadline() {
        when(ai.page("workspace-a", "page-a")).thenAnswer(call -> {
            Thread.sleep(5_000);
            return Optional.of(page("# late", null));
        });
        long started = System.nanoTime();

        assertThatThrownBy(() -> service(10_000, 100).get(identity(), "page-a")).hasMessage("DEPENDENCY_UNAVAILABLE");
        assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
    }

    private McpWikiPageService service(int bodyBytes) {
        return service(bodyBytes, 5_000);
    }

    private McpWikiPageService service(int bodyBytes, long deadlineMs) {
        var p = McpTokenVerifierTest.props();
        var properties = new McpProperties(p.resourceUri(), p.authorizationServerUri(), p.allowedOrigins(),
                p.wikiSearchUri(), p.cursorSecret(), p.maxPageSize(), p.maxCandidates(), p.maxResponseBytes(),
                p.maxCursorBytes(), p.maxSourceBlocks(), p.maxSourceBytes(), p.maxDocumentBlocksPerPage(),
                bodyBytes, p.dependencyTimeoutSeconds());
        return new McpWikiPageService(web, ai, versions, properties, deadlineMs);
    }

    private static WikiPageDetailResponse page(String markdown, String markdownUri) {
        return new WikiPageDetailResponse("page-a", "source", "Title", "title", null, markdownUri, markdown,
                "active", null, null, UPDATED, List.of(), List.of());
    }

    private static McpIdentity identity() {
        return new McpIdentity("user-a", "workspace-a", "https://client.example/client.json", "grant-a", 1,
                Set.of("read"), "MEMBER", Set.of());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
