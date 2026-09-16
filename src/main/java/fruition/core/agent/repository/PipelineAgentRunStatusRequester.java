package fruition.core.agent.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

@Component
public class PipelineAgentRunStatusRequester {

    private final RestClient restClient;
    private final RestClient autonomousRunRestClient;
    private final String endpoint;
    private final String autonomousRunEndpoint;

    public PipelineAgentRunStatusRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.agent.status-endpoint}") String endpoint,
            @Value("${app.agent.run-endpoint}") String autonomousRunEndpoint,
            @Value("${app.skill.agent-token}") String agentToken,
            @Value("${app.agent.status-timeout-seconds:5}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.autonomousRunRestClient = RestClient.builder()
                .requestFactory(clientFactory.requestFactory(timeoutSeconds))
                .defaultHeader("X-Agent-Service-Token", agentToken)
                .build();
        this.endpoint = endpoint;
        this.autonomousRunEndpoint = autonomousRunEndpoint;
    }

    public Optional<AutonomousRun> findAutonomous(String workspaceId, String userId, String runId) {
        try {
            return Optional.ofNullable(autonomousRunRestClient.get()
                    .uri(autonomousRunEndpoint + "/{runId}?workspace_id={workspaceId}&user_id={userId}",
                            runId, workspaceId, userId)
                    .retrieve()
                    .body(AutonomousRun.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (ResourceAccessException e) {
            throw new PipelineAgentException("AgentRun 조회 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            throw new PipelineAgentException("AgentRun을 조회하지 못했습니다.", 503,
                    e.getResponseBodyAsString());
        }
    }

    public JsonNode getAutonomousRun(String workspaceId, String userId, String runId) {
        return requestAutonomous(() -> autonomousRunRestClient.get()
                .uri(autonomousUri(workspaceId, userId, runId))
                .retrieve()
                .body(JsonNode.class), runId);
    }

    public JsonNode approve(String workspaceId, String userId, String runId,
                            int planVersion, String operationHash) {
        return postAutonomous(runId, new ApproveRequest(workspaceId, userId, planVersion, operationHash), "/approve");
    }

    public JsonNode reject(String workspaceId, String userId, String runId) {
        return postAutonomous(runId, new ActorRequest(workspaceId, userId), "/reject");
    }

    public JsonNode cancel(String workspaceId, String userId, String runId) {
        return postAutonomous(runId, new ActorRequest(workspaceId, userId), "/cancel");
    }

    public JsonNode revise(String workspaceId, String userId, String runId, String instruction) {
        return postAutonomous(runId, new ReviseRequest(workspaceId, userId, instruction), "/revise");
    }

    private JsonNode postAutonomous(String runId, Object body, String action) {
        return requestAutonomous(() -> autonomousRunRestClient.post()
                .uri(autonomousRunEndpoint + "/{runId}" + action, runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class), runId);
    }

    private JsonNode requestAutonomous(java.util.function.Supplier<JsonNode> request, String runId) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new AgentRunNotFoundException(runId);
        } catch (ResourceAccessException e) {
            throw new PipelineAgentException("AgentRun 요청 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().is4xxClientError() ? e.getStatusCode().value() : 503;
            throw new PipelineAgentException("AgentRun 요청이 거부되었습니다.", status, null);
        }
    }

    private URI autonomousUri(String workspaceId, String userId, String runId) {
        return UriComponentsBuilder.fromUriString(autonomousRunEndpoint)
                .pathSegment(runId)
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .build()
                .encode()
                .toUri();
    }

    public Optional<RunStatus> find(String workspaceId, String userId, String runId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(endpoint + "/{runId}?workspace_id={workspaceId}&user_id={userId}",
                            runId, workspaceId, userId)
                    .retrieve()
                    .body(RunStatus.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (ResourceAccessException e) {
            throw new PipelineAgentException("Agent 상태 조회 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            throw new PipelineAgentException("Agent 상태를 조회하지 못했습니다.", 503,
                    e.getResponseBodyAsString());
        }
    }

    /** 문서를 열지 않은 턴은 documentId·baseVersion·applyOperationId가 모두 null이다. */
    public record RunStatus(
            String id,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") Long baseVersion,
            @JsonProperty("apply_operation_id") String applyOperationId,
            String status,
            JsonNode result,
            @JsonProperty("error_code") String errorCode
    ) {}

    public record AutonomousRun(
            String id,
            String action,
            String status,
            @JsonProperty("request_summary") String requestSummary,
            Plan plan
    ) {}

    public record Plan(
            String summary,
            java.util.List<Operation> operations
    ) {}

    public record Operation(
            @JsonProperty("tool_name") String toolName,
            String reason,
            String status
    ) {}

    private record ActorRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId
    ) {}

    private record ApproveRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("plan_version") int planVersion,
            @JsonProperty("operation_hash") String operationHash
    ) {}

    private record ReviseRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String instruction
    ) {}
}
