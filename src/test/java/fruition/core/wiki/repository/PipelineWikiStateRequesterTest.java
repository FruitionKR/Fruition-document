package fruition.core.wiki.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineWikiStateRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/wiki/pages", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/wiki/documents", exchange -> {
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void documentContext_readsBlockPositionsAndSnapshotHash() {
        responseBody.set("""
                {
                  "pages": [],
                  "source_content_hash": "9f2c",
                  "source_blocks": [
                    {"block_id":"B0440","position":2,"line_start":3,"line_end":5,
                     "block_type":"paragraph","text":"균형 규칙"}
                  ]
                }
                """);

        var context = requester().documentContext("ws_1", "doc_1");

        assertThat(context.sourceContentHash()).isEqualTo("9f2c");
        assertThat(context.sourceBlocks()).containsExactly(
                new PipelineWikiStateRequester.SourceBlock("B0440", 2, 3, 5, "paragraph", "균형 규칙"));
    }

    @Test
    void documentContext_acceptsResponseWithoutPositionFields() {
        // AI 선행 변경(Fruition-ai#42) 배포 전 응답도 그대로 받아야 한다.
        responseBody.set("""
                {"pages": [], "source_blocks": [{"block_id":"B0001","text":"본문"}]}
                """);

        var context = requester().documentContext("ws_1", "doc_1");

        assertThat(context.sourceContentHash()).isNull();
        assertThat(context.sourceBlocks()).containsExactly(
                new PipelineWikiStateRequester.SourceBlock("B0001", null, null, null, null, "본문"));
    }

    @Test
    void page_readsConceptStatementsLinksAndLintTargets() {
        responseBody.set("""
                {"id":"wp_1","page_type":"concept","title":"캐시","slug":"cache","status":"active",
                 "source_documents":[],"related_pages":[],
                 "statements":[{"statement_id":"st_1","text":"문장","text_hash":"sha256:ab"}],
                 "links":[{"page_id":"wp_2","slug":"index","relation":"concept_related_to","direction":"outgoing",
                           "source_ref":"st_1","target_ref":"wp_2"}],
                 "lint_target_page_ids":["wp_1","wp_2"],"lint_target_truncated":false}
                """);

        var page = requester().page("ws_1", "wp_1").orElseThrow();

        assertThat(page.slug()).isEqualTo("cache");
        assertThat(page.statements()).containsExactly(
                new fruition.core.wiki.dto.WikiPageStatement("st_1", "문장", "sha256:ab"));
        assertThat(page.links()).containsExactly(new fruition.core.wiki.dto.WikiPageLink(
                "wp_2", "index", "concept_related_to", "outgoing", "st_1", "wp_2"));
        assertThat(page.lintTargetPageIds()).containsExactly("wp_1", "wp_2");
        assertThat(page.lintTargetTruncated()).isFalse();
    }

    @Test
    void page_acceptsResponseWithoutLintFields() {
        responseBody.set("""
                {"id":"wp_1","page_type":"source","title":"원문","source_documents":[],"related_pages":[]}
                """);

        var page = requester().page("ws_1", "wp_1").orElseThrow();

        assertThat(page.statements()).isNull();
        assertThat(page.links()).isNull();
        assertThat(page.lintTargetPageIds()).isNull();
        assertThat(page.lintTargetTruncated()).isNull();
    }

    @Test
    void mcpPageReadAsksForLintContextButWebReadDoesNot() {
        responseBody.set("""
                {"id":"wp_1","page_type":"concept","title":"개념","source_documents":[],"related_pages":[],
                 "statements":[],"links":[],"lint_target_page_ids":["wp_1"],"lint_target_truncated":false,
                 "lint_context_error":"STATEMENTS_UNAVAILABLE"}
                """);

        var page = requester().page("ws_1", "wp_1", true).orElseThrow();
        assertThat(lastQuery.get()).contains("workspace_id=ws_1").contains("mcp_read=true");
        assertThat(page.lintContextError()).isEqualTo("STATEMENTS_UNAVAILABLE");

        requester().page("ws_1", "wp_1");
        assertThat(lastQuery.get()).isEqualTo("workspace_id=ws_1");
    }

    private PipelineWikiStateRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki";
        return new PipelineWikiStateRequester(new PipelineClientFactory("test-internal-callback"), endpoint, 5);
    }
}
