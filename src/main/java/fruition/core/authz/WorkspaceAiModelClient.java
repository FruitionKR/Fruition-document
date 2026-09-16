package fruition.core.authz;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;

@Component
public class WorkspaceAiModelClient {
    private final RestClient restClient;

    public WorkspaceAiModelClient(
            @Value("${app.internal.access-base-url}") String accessBaseUrl,
            @Value("${app.internal.callback-token}") String internalToken) {
        this.restClient = RestClient.builder()
                .baseUrl(accessBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public AiModelSelection get(String workspaceId) {
        WorkspaceAiModelResponse response = restClient.get()
                .uri("/internal/workspaces/{workspaceId}/ai-model-settings", workspaceId)
                .retrieve()
                .body(WorkspaceAiModelResponse.class);
        if (response == null || response.ingestLint() == null) {
            throw new IllegalStateException("워크스페이스 AI 모델 설정 응답이 비어 있습니다.");
        }
        return response.ingestLint();
    }

    public AiModelSelection update(String workspaceId, String provider, String model) {
        WorkspaceAiModelResponse response = restClient.put()
                .uri("/internal/workspaces/{workspaceId}/ai-model-settings", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WorkspaceAiModelRequest(new AiModelSelection(provider, model)))
                .retrieve()
                .body(WorkspaceAiModelResponse.class);
        if (response == null || response.ingestLint() == null) {
            throw new IllegalStateException("워크스페이스 AI 모델 설정 응답이 비어 있습니다.");
        }
        return response.ingestLint();
    }

    public record WorkspaceAiModelResponse(
            @JsonProperty("ingest_lint") AiModelSelection ingestLint) {}
    public record WorkspaceAiModelRequest(
            @JsonProperty("ingest_lint") AiModelSelection ingestLint) {}
    // WorkspaceAiModelSettingsController의 동명 record와 겹치지 않도록 스키마 이름을 분리한다.
    @Schema(name = "AiModelSelectionResponse", description = "현재 설정된 provider와 model 조합")
    public record AiModelSelection(
            @Schema(description = "LLM provider", allowableValues = {"openai", "gemini", "claude"},
                    example = "openai")
            String provider,

            @Schema(description = "모델명", example = "gpt-5-nano")
            String model) {}
}
