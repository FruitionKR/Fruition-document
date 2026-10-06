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

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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

    private PipelineWikiStateRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki";
        return new PipelineWikiStateRequester(new PipelineClientFactory("test-internal-callback"), endpoint, 5);
    }
}
