package fruition.core.usage.service;

import com.sun.net.httpserver.HttpServer;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelUsageServiceTest {
    @Test
    void scopesAndAuthenticatesInternalRequestAndRejectsNonMember() throws Exception {
        var requests = new AtomicInteger();
        var invalidResponse = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/usage/models", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("internal-test");
            assertThat(exchange.getRequestURI().getQuery()).contains("workspace_id=workspace", "user_id=member");
            var bytes = (invalidResponse.get() ? "{}" : "{\"models\":[{\"model\":\"test\",\"known_input_tokens\":100}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var guard = mock(WorkspaceAccessGuard.class);
            var service = new ModelUsageService(guard, new PipelineClientFactory("internal-test"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/usage/models");
            var result = service.read("workspace", "member", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"));
            assertThat(result.path("models").get(0).path("known_input_tokens").asInt()).isEqualTo(100);
            verify(guard).requireMember("workspace", "member");
            doThrow(new WorkspaceNotFoundException("workspace")).when(guard).requireMember("workspace", "outsider");
            assertThatThrownBy(() -> service.read("workspace", "outsider", null, null)).isInstanceOf(WorkspaceNotFoundException.class);
            assertThat(requests.get()).isEqualTo(1);
            assertThatThrownBy(() -> service.read("workspace", "member", Instant.now(), Instant.EPOCH))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(requests.get()).isEqualTo(1);
            invalidResponse.set(true);
            assertThatThrownBy(() -> service.read("workspace", "member", null, null))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        } finally {
            server.stop(0);
        }
    }
}
