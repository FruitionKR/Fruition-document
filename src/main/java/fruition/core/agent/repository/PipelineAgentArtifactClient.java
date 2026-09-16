package fruition.core.agent.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class PipelineAgentArtifactClient {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineAgentArtifactClient(
            PipelineClientFactory clientFactory,
            @Value("${app.agent.status-endpoint}") String agentRunEndpoint,
            @Value("${app.agent.status-timeout-seconds:5}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = agentRunEndpoint + "/artifacts";
    }

    public List<ArtifactMetadata> list(AgentToolReadRequest request) {
        try {
            return restClient.post()
                    .uri(endpoint + "/list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ArtifactActorRequest(request.runId(), request.workspaceId(), request.userId()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ArtifactMetadata>>() {});
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(e.getStatusCode(), "Agent artifact를 확인할 수 없습니다.", e);
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent artifact 확인 시간이 초과되었습니다.", e);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode().is5xxServerError()
                    ? HttpStatus.SERVICE_UNAVAILABLE : e.getStatusCode();
            throw new ResponseStatusException(status, "Agent artifact를 확인할 수 없습니다.", e);
        }
    }

    public ResolvedArtifact resolve(AgentToolExecuteRequest request, String purpose, JsonNode arguments) {
        try {
            return restClient.post()
                    .uri(endpoint + "/resolve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ArtifactResolveRequest(
                            request.runId(), request.workspaceId(), request.userId(),
                            text(arguments, "content_artifact_id"), text(arguments, "content_hash"),
                            purpose,
                            nullableText(arguments, "document_id"),
                            nullableLong(arguments, "base_version"),
                            arguments.get("target")))
                    .retrieve()
                    .body(ResolvedArtifact.class);
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(e.getStatusCode(), "Agent artifact를 확인할 수 없습니다.", e);
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent artifact 확인 시간이 초과되었습니다.", e);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode().is5xxServerError()
                    ? HttpStatus.SERVICE_UNAVAILABLE : e.getStatusCode();
            throw new ResponseStatusException(status, "Agent artifact를 확인할 수 없습니다.", e);
        }
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 값이 올바르지 않습니다.");
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value == null || value.isNull() ? null : text(arguments, field);
    }

    private static Long nullableLong(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value == null || value.isNull() ? null : value.longValue();
    }

    private record ArtifactActorRequest(
            @JsonProperty("run_id") String runId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId
    ) {}

    private record ArtifactResolveRequest(
            @JsonProperty("run_id") String runId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("artifact_id") String artifactId,
            @JsonProperty("content_hash") String contentHash,
            String purpose,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") Long baseVersion,
            JsonNode target
    ) {}

    public record ArtifactMetadata(
            String id,
            @JsonProperty("content_hash") String contentHash,
            String purpose,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") Long baseVersion,
            JsonNode target
    ) {}

    public record ResolvedArtifact(
            String id,
            @JsonProperty("content_hash") String contentHash,
            String purpose,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") Long baseVersion,
            JsonNode target,
            String markdown
    ) {}
}
