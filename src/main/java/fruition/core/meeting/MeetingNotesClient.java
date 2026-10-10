package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.usage.service.UsageChargeService;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/** ai-svc 회의록 초안(`POST /meeting-notes/preview`) 호출. AI는 저장하지 않고 초안만 돌려준다. */
@Component
public class MeetingNotesClient {
    private final RestClient restClient;
    private final String endpoint;
    private final UsageChargeService usageCharges;

    public MeetingNotesClient(PipelineClientFactory clientFactory,
                              @Value("${app.speech.meeting-notes-endpoint}") String endpoint,
                              @Value("${app.speech.meeting-notes-timeout-seconds:270}") int timeoutSeconds,
                              UsageChargeService usageCharges) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
        this.usageCharges = usageCharges;
    }

    /**
     * 실패는 사용자용 오류 코드로 바꾼다. 제공자 원문은 노출하지 않는다. AI가 사용량을 남기도록 run_id를
     * {@code X-Request-Id} 헤더로 보낸다. 본문에 넣으면 ai-svc가 모르는 필드로 보고 422로 거부한다.
     * 워크스페이스에서 고른 모델은 본문의 provider·model로 보낸다(없으면 ai-svc가 422로 거부한다).
     */
    public JsonNode preview(String workspaceId, String userId, String displayName, List<Map<String, String>> segments,
                            WorkspaceAiModelClient.AiModelSelection model) {
        return usageCharges.track("meeting_notes", workspaceId, userId,
                runId -> preview(runId, workspaceId, userId, displayName, segments, model));
    }

    private JsonNode preview(String runId, String workspaceId, String userId, String displayName,
                             List<Map<String, String>> segments, WorkspaceAiModelClient.AiModelSelection model) {
        try {
            JsonNode body = restClient.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .header(UsageChargeService.RUN_ID_HEADER, runId)
                    .body(Map.of("workspace_id", workspaceId, "user_id", userId,
                            "display_name", displayName, "segments", segments,
                            "provider", model.provider(), "model", model.model()))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.path("summary").isArray()) {
                throw new MeetingException(HttpStatus.BAD_GATEWAY, "MEETING_NOTES_INVALID", "회의록 형식이 올바르지 않습니다.");
            }
            return body;
        } catch (ResourceAccessException e) {
            throw new MeetingException(HttpStatus.SERVICE_UNAVAILABLE, "MEETING_NOTES_UNAVAILABLE", "회의록 생성 서비스를 사용할 수 없습니다.");
        } catch (RestClientResponseException e) {
            throw switch (e.getStatusCode().value()) {
                case 422 -> new MeetingException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_NOTES_INPUT_REJECTED",
                        "전사가 회의록 생성 한도를 넘었습니다.");
                case 502 -> new MeetingException(HttpStatus.BAD_GATEWAY, "MEETING_NOTES_FAILED",
                        "회의록을 생성하지 못했습니다. 다시 시도해 주세요.");
                default -> new MeetingException(HttpStatus.SERVICE_UNAVAILABLE, "MEETING_NOTES_UNAVAILABLE",
                        "회의록 생성 서비스를 사용할 수 없습니다.");
            };
        }
    }
}
