package fruition.core.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineAgentToolAuthorizationClientTest {

    private HttpServer server;
    private final AtomicReference<String> capturedToken = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(204);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/agent/runs/tool-authorizations/execute", exchange -> {
            capturedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = responseStatus.get();
            byte[] body = status == 204 ? new byte[0] : "conflict".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void executeAuthorizationUsesInternalTokenAndExactContract() throws Exception {
        client().authorizeExecute("create_folder", request());

        assertThat(capturedToken.get()).isEqualTo("internal-token");
        assertThat(capturedBody.get())
                .contains("\"run_id\":\"run-1\"")
                .contains("\"tool_name\":\"create_folder\"")
                .contains("\"arguments\":{\"name\":\"새 폴더\",\"parent_folder_id\":null}")
                .doesNotContain("idempotency_key");
    }

    @Test
    void executeAuthorizationPreservesApprovalConflictStatus() throws Exception {
        responseStatus.set(409);

        assertThatThrownBy(() -> client().authorizeExecute("create_folder", request()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    private PipelineAgentToolAuthorizationClient client() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/internal/agent/runs";
        return new PipelineAgentToolAuthorizationClient(
                new PipelineClientFactory("internal-token"), endpoint, 5);
    }

    private AgentToolExecuteRequest request() throws Exception {
        return new AgentToolExecuteRequest(
                "run-1", "workspace-1", "user-1", "plan-1", 1, "a".repeat(64),
                "operation-1", "idem-1",
                new ObjectMapper().readTree("{\"name\":\"새 폴더\",\"parent_folder_id\":null}"));
    }
}
