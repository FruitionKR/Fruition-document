package fruition.core.document.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class PipelineRunStatusRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineRunStatusRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.pipeline-run.endpoint}") String endpoint,
            @Value("${app.pipeline-run.timeout-seconds:5}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
    }

    public Optional<PipelineRunStatus> find(String runId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(endpoint + "/" + runId)
                    .retrieve()
                    .body(PipelineRunStatus.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public record PipelineRunStatus(
            String id,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String mode,
            String status,
            String error,
            JsonNode manifest
    ) {}
}
