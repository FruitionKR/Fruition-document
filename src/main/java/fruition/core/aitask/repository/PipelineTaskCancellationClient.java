package fruition.core.aitask.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class PipelineTaskCancellationClient {
    private final RestClient client;
    private final String endpoint;

    public PipelineTaskCancellationClient(PipelineClientFactory factory,
                                          @Value("${app.agent.status-endpoint}") String agentEndpoint) {
        client = factory.restClient(30);
        endpoint = URI.create(agentEndpoint).resolve("/internal/ai/tasks").toString();
    }

    public TaskStatus cancel(String runId, String workspaceId, String userId, JsonNode command) {
        return client.post().uri(endpoint + "/{id}/cancel", runId)
                .body(Map.of("workspace_id", workspaceId, "user_id", userId, "command", command))
                .retrieve().body(TaskStatus.class);
    }

    public void rollbackBackend(String runId, String workspaceId, String userId, JsonNode command) {
        client.post().uri(endpoint + "/{id}/rollback-backend", runId)
                .body(Map.of("workspace_id", workspaceId, "user_id", userId, "command", command))
                .retrieve().toBodilessEntity();
    }

    public TaskStatus status(String runId, String workspaceId, String userId) {
        return client.get().uri(UriComponentsBuilder.fromUriString(endpoint).path("/{id}")
                        .queryParam("workspace_id", workspaceId).queryParam("user_id", userId)
                        .buildAndExpand(runId).encode().toUri())
                .retrieve().body(TaskStatus.class);
    }

    public boolean cancelDocumentTasks(String documentId, String workspaceId, String userId) {
        var response = client.post().uri(endpoint + "/documents/{id}/cancel", documentId)
                .body(Map.of("workspace_id", workspaceId, "user_id", userId))
                .retrieve().body(DocumentCleanup.class);
        return response != null && response.cleanupComplete();
    }

    public record TaskStatus(String id, String status, @JsonProperty("error_code") String errorCode) {}
    private record DocumentCleanup(@JsonProperty("cleanup_complete") boolean cleanupComplete) {}
}
