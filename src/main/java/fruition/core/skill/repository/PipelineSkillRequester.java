package fruition.core.skill.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PipelineSkillRequester {

    private final RestClient restClient;
    private final String endpoint;
    private final fruition.core.aitask.service.AiTaskCancellationService cancellation;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public PipelineSkillRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.skill.endpoint}") String endpoint,
            @Value("${app.skill.agent-token}") String agentToken,
            @Value("${app.skill.timeout-seconds:60}") int timeoutSeconds,
            fruition.core.aitask.service.AiTaskCancellationService cancellation, com.fasterxml.jackson.databind.ObjectMapper mapper
    ) {
        this.endpoint = endpoint;
        this.cancellation = cancellation;
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .requestFactory(clientFactory.requestFactory(timeoutSeconds))
                .defaultHeader("X-Agent-Service-Token", agentToken)
                .build();
    }

    public JsonNode author(String workspaceId, String userId, SkillAuthoringRequest request,
                           WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        return task(runId, "skill_author", workspaceId, userId, null, new AuthorPayload(workspaceId, userId, request.scopeType(), request.name(),
                request.description(), request.instruction(), request.authoringMode(), request.referenceDocumentIds(),
                aiModel.provider(), aiModel.model()));
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request,
                            WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        return task(runId, "skill_publish", workspaceId, userId, null, new PublishPayload(workspaceId, userId, request.scopeType(), request.name(),
                request.description(), request.instructionsMarkdown(), request.capabilities(), request.allowedTools(),
                aiModel.provider(), aiModel.model()));
    }

    public JsonNode list(String workspaceId, String userId) {
        String uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
        return get(uri);
    }

    public JsonNode get(String workspaceId, String userId, String skillId) {
        String uri = UriComponentsBuilder.fromUriString(endpoint + "/" + skillId)
                .queryParam("workspace_id", workspaceId)
                .queryParam("user_id", userId)
                .toUriString();
        return get(uri);
    }

    public JsonNode update(String workspaceId, String userId, String skillId, SkillUpdateRequest request,
                           WorkspaceAiModelClient.AiModelSelection aiModel, String runId) {
        return task(runId, "skill_update", workspaceId, userId, skillId, new UpdatePayload(workspaceId, userId, request.name(),
                request.description(), request.instructionsMarkdown(), aiModel.provider(), aiModel.model()));
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        return post("/" + skillId + (enabled ? "/enable" : "/disable"),
                new ActorPayload(workspaceId, userId));
    }

    private JsonNode task(String runId, String kind, String workspaceId, String userId, String skillId, Object payload) {
        var command = mapper.createObjectNode().put("run_id", runId).put("kind", kind)
                .put("workspace_id", workspaceId).put("user_id", userId);
        command.set("payload", mapper.valueToTree(payload));
        if (skillId != null) command.put("skill_id", skillId);
        cancellation.register(command);
        JsonNode result = post("/tasks", command);
        cancellation.finish(runId);
        return result;
    }

    private JsonNode get(String uri) {
        try {
            return requireBody(restClient.get().uri(uri).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            return requireBody(restClient.post().uri(endpoint + path).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode requireBody(JsonNode response) {
        if (response == null || response.isNull()) {
            throw unavailable("Skill 파이프라인 응답이 비어 있습니다.");
        }
        return response;
    }

    private PipelineSkillException translate(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 400 || status == 404 || status == 409 || status == 410 || status == 413 || status == 422) {
            return new PipelineSkillException(
                    "Skill 요청이 거부되었습니다.", status, exception.getResponseBodyAsString());
        }
        return unavailable("Skill 파이프라인을 사용할 수 없습니다.");
    }

    private PipelineSkillException unavailable(String message) {
        return new PipelineSkillException(message, 503, null);
    }

    private record AuthorPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            String instruction,
            @JsonProperty("authoring_mode") String authoringMode,
            @JsonProperty("reference_document_ids") java.util.List<String> referenceDocumentIds,
            String provider,
            String model) {}

    private record PublishPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown,
            java.util.List<String> capabilities,
            @JsonProperty("allowed_tools") java.util.List<String> allowedTools,
            String provider,
            String model) {}

    private record UpdatePayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown,
            String provider,
            String model) {}

    private record ActorPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId) {}
}
