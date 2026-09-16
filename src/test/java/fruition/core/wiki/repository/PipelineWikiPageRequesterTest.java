package fruition.core.wiki.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.core.wiki.dto.WikiPageRenameRequest;
import fruition.core.wiki.dto.WikiPageRenameResponse;
import fruition.core.wiki.exception.PipelineWikiPageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineWikiPageRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private final AtomicReference<String> capturedUri = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("""
            {
              "id":"wp_1",
              "page_type":"concept",
              "title":"새 제목",
              "previous_title":"이전 제목",
              "slug":"new-title",
              "previous_slug":"old-title",
              "slug_updated":true,
              "updated_at":"2026-07-28T00:00:00Z"
            }
            """);
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/wiki/pages", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedUri.set(exchange.getRequestURI().toString());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
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
    void rename_sendsScopeAndRenameFieldsToPipeline() {
        WikiPageRenameResponse response = requester().rename(
                "ws_1", "user_1", "wp_1", new WikiPageRenameRequest("새 제목", true));

        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.slugUpdated()).isTrue();
        assertThat(capturedMethod.get()).isEqualTo("PATCH");
        assertThat(capturedUri.get()).isEqualTo("/wiki/pages/wp_1/rename");
        assertThat(capturedBody.get())
                .contains("\"user_id\":\"user_1\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"title\":\"새 제목\"")
                .contains("\"update_slug\":true");
    }

    @Test
    void rename_preservesExpectedPipelineError() {
        responseStatus.set(409);
        responseBody.set("{\"code\":\"WIKI_PAGE_SLUG_CONFLICT\",\"message\":\"slug conflict\"}");

        assertThatThrownBy(() -> requester().rename(
                "ws_1", "user_1", "wp_1", new WikiPageRenameRequest("중복", true)))
                .isInstanceOfSatisfying(PipelineWikiPageException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                    assertThat(error.getResponseBody()).contains("WIKI_PAGE_SLUG_CONFLICT");
                });
    }

    @Test
    void rename_mapsPipelineServerErrorTo503() {
        responseStatus.set(500);
        responseBody.set("{\"detail\":\"boom\"}");

        assertThatThrownBy(() -> requester().rename(
                "ws_1", "user_1", "wp_1", new WikiPageRenameRequest("제목", false)))
                .isInstanceOfSatisfying(PipelineWikiPageException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(503);
                    assertThat(error.getResponseBody()).isNull();
                });
    }

    private PipelineWikiPageRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki/pages";
        return new PipelineWikiPageRequester(
                new fruition.shared.http.PipelineClientFactory("test-internal-callback"), endpoint, 5);
    }
}
