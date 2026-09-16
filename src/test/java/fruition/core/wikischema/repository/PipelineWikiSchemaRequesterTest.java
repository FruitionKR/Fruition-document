package fruition.core.wikischema.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import fruition.core.wikischema.exception.PipelineWikiSchemaException;
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

class PipelineWikiSchemaRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> capturedUri = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"preview_markdown\":\"# 미리보기\"}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/wiki-schema", exchange -> {
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
    void preview_sendsRawMarkdownSnakeCase() {
        JsonNode response = requester().preview("# 원문");

        assertThat(response.path("preview_markdown").asText()).isEqualTo("# 미리보기");
        assertThat(capturedUri.get()).isEqualTo("/wiki-schema/preview");
        assertThat(capturedBody.get())
                .contains("\"raw_markdown\":\"# 원문\"")
                .doesNotContain("rawMarkdown");
    }

    @Test
    void createDraft_sendsWorkspaceAndUserSnakeCase() {
        responseBody.set("{\"wiki_schema\":{\"id\":\"sch_1\"}}");

        JsonNode response = requester().createDraft("# 원문", "기본", "ws_1", "user_1");

        assertThat(response.path("wiki_schema").path("id").asText()).isEqualTo("sch_1");
        assertThat(capturedUri.get()).isEqualTo("/wiki-schema/drafts");
        assertThat(capturedBody.get())
                .contains("\"raw_markdown\":\"# 원문\"")
                .contains("\"name\":\"기본\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"");
    }

    @Test
    void createDraft_omitsNullName() {
        responseBody.set("{\"wiki_schema\":{\"id\":\"sch_1\"}}");

        requester().createDraft("# 원문", null, "ws_1", "user_1");

        assertThat(capturedBody.get()).doesNotContain("\"name\"");
    }

    @Test
    void activate_usesSchemaIdPath() {
        responseBody.set("{\"id\":\"sch_1\",\"status\":\"active\"}");

        JsonNode response = requester().activate("sch_1");

        assertThat(response.path("status").asText()).isEqualTo("active");
        assertThat(capturedUri.get()).isEqualTo("/wiki-schema/sch_1/activate");
    }

    @Test
    void getActive_passesQueryParamsAndReturnsNullWhenAbsent() {
        responseBody.set("null");

        JsonNode response = requester().getActive("ws_1", "user_1");

        assertThat(response.isNull()).isTrue();
        assertThat(capturedUri.get()).contains("workspace_id=ws_1").contains("user_id=user_1");
    }

    @Test
    void preview_preservesPipeline422Body() {
        responseStatus.set(422);
        responseBody.set("{\"detail\":[{\"loc\":[\"body\",\"raw_markdown\"],\"msg\":\"too short\"}]}");

        assertThatThrownBy(() -> requester().preview("x"))
                .isInstanceOfSatisfying(PipelineWikiSchemaException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(422);
                    assertThat(error.getResponseBody()).contains("too short");
                });
    }

    @Test
    void activate_preservesPipeline404Body() {
        responseStatus.set(404);
        responseBody.set("{\"detail\":\"schema not found\"}");

        assertThatThrownBy(() -> requester().activate("missing"))
                .isInstanceOfSatisfying(PipelineWikiSchemaException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                    assertThat(error.getResponseBody()).contains("schema not found");
                });
    }

    @Test
    void preview_mapsServerErrorTo503() {
        responseStatus.set(500);
        responseBody.set("{\"detail\":\"boom\"}");

        assertThatThrownBy(() -> requester().preview("x"))
                .isInstanceOfSatisfying(PipelineWikiSchemaException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(503);
                    assertThat(error.getResponseBody()).isNull();
                });
    }

    private PipelineWikiSchemaRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki-schema";
        return new PipelineWikiSchemaRequester(
                new fruition.shared.http.PipelineClientFactory("test-internal-callback"), endpoint, 5);
    }
}
