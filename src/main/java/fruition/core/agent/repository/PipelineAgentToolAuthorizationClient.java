package fruition.core.agent.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PipelineAgentToolAuthorizationClient {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineAgentToolAuthorizationClient(
            PipelineClientFactory clientFactory,
            @Value("${app.agent.status-endpoint}") String agentRunEndpoint,
            @Value("${app.agent.status-timeout-seconds:5}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = agentRunEndpoint + "/tool-authorizations";
    }

    public void authorizeRead(AgentToolReadRequest request) {
        authorize("/read", new ReadAuthorizationRequest(
                request.runId(), request.workspaceId(), request.userId()));
    }

    public void authorizeExecute(String toolName, AgentToolExecuteRequest request) {
        authorize("/execute", new ExecuteAuthorizationRequest(
                request.runId(), request.workspaceId(), request.userId(), request.planId(),
                request.planVersion(), request.operationHash(), request.operationId(), toolName,
                request.arguments()));
    }

    private void authorize(String path, Object body) {
        try {
            restClient.post()
                    .uri(endpoint + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(e.getStatusCode(), "Agent Tool 승인을 확인할 수 없습니다.", e);
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent Tool 승인 확인 시간이 초과되었습니다.", e);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode().is5xxServerError()
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : e.getStatusCode();
            throw new ResponseStatusException(status, "Agent Tool 승인을 확인할 수 없습니다.", e);
        }
    }

    private record ReadAuthorizationRequest(
            @JsonProperty("run_id") String runId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId
    ) {}

    private record ExecuteAuthorizationRequest(
            @JsonProperty("run_id") String runId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("plan_id") String planId,
            @JsonProperty("plan_version") int planVersion,
            @JsonProperty("operation_hash") String operationHash,
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("tool_name") String toolName,
            JsonNode arguments
    ) {}
}
