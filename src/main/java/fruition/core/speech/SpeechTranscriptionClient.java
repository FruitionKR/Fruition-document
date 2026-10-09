package fruition.core.speech;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.usage.service.UsageChargeService;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/** ai-svc 파일 전사(`POST /speech/transcriptions`) 호출. 오디오·결과를 저장하지 않는다. */
@Component
public class SpeechTranscriptionClient {
    private final RestClient restClient;
    private final String endpoint;
    private final UsageChargeService usageCharges;

    public SpeechTranscriptionClient(PipelineClientFactory clientFactory,
                                     @Value("${app.speech.transcription-endpoint}") String endpoint,
                                     @Value("${app.speech.transcription-timeout-seconds:150}") int timeoutSeconds,
                                     UsageChargeService usageCharges) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
        this.usageCharges = usageCharges;
    }

    /** AI가 사용량을 남기도록 run_id를 함께 보낸다. */
    public String transcribe(String workspaceId, String userId, MediaType mediaType, byte[] audio) {
        return usageCharges.track("speech_transcription", workspaceId, userId,
                runId -> transcribe(runId, workspaceId, userId, mediaType, audio));
    }

    private String transcribe(String runId, String workspaceId, String userId, MediaType mediaType, byte[] audio) {
        URI uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("workspace_id", "{workspaceId}")
                .queryParam("user_id", "{userId}")
                .queryParam("run_id", "{runId}")
                .encode()
                .buildAndExpand(workspaceId, userId, runId)
                .toUri();
        try {
            JsonNode body = restClient.post().uri(uri).contentType(mediaType).body(audio)
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.path("text").isTextual()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성을 전사하지 못했습니다.");
            }
            return body.path("text").asText();
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "음성 전사 서비스에 연결하지 못했습니다.", e);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    /** ai-svc의 입력 오류는 그대로, 인증·설정 문제는 503, 제공자 실패는 502로 돌려준다. 원문 메시지는 노출하지 않는다. */
    private static ResponseStatusException translate(RestClientResponseException e) {
        return switch (e.getStatusCode().value()) {
            case 403 -> new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 접근 권한이 없습니다.", e);
            case 413 -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "음성 파일은 24 MiB 이하로 보내주세요.", e);
            case 415 -> new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "WAV, MP3, M4A, WebM 음성을 보내주세요.", e);
            case 422 -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "음성을 인식할 수 없습니다.", e);
            case 502 -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "음성을 전사하지 못했습니다.", e);
            default -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "음성 전사 서비스를 사용할 수 없습니다.", e);
        };
    }
}
