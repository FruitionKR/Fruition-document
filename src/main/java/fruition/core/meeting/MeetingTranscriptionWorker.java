package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.usage.service.UsageChargeService;
import fruition.shared.http.PipelineClientFactory;
import fruition.shared.util.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 녹음 파일 회의의 전사 작업자. {@code status = 'transcribing'}인 회의를 한 건씩 선점해 AI 파일 전사를 부르고,
 * 결과를 문장 단위 구간으로 저장한다. 실패하면 사유를 남기고 사용자가 다시 올린다(자동 재시도 없음).
 */
@Component
public class MeetingTranscriptionWorker {
    static final Duration STALE_CLAIM = Duration.ofMinutes(5);
    static final int MAX_SEGMENT_CHARS = 10_000;
    /** 회의록 AI는 구간 1,000개·전체 100,000자까지 받는다. 문장을 이 길이까지 묶어 구간 수를 한도 안에 둔다. */
    static final int TARGET_SEGMENT_CHARS = 1_000;
    static final int MAX_TOTAL_CHARS = 100_000;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.?!。？！])\\s+|\\n+");
    private static final Logger log = LoggerFactory.getLogger(MeetingTranscriptionWorker.class);

    private final MeetingRepository repository;
    private final MinioClient minio;
    private final StorageProperties storage;
    private final RestClient restClient;
    private final String endpoint;
    private final UsageChargeService usageCharges;

    public MeetingTranscriptionWorker(MeetingRepository repository, MinioClient minio, StorageProperties storage,
                                      PipelineClientFactory clientFactory,
                                      @Value("${app.speech.transcription-endpoint}") String endpoint,
                                      @Value("${app.speech.transcription-timeout-seconds:150}") int timeoutSeconds,
                                      UsageChargeService usageCharges) {
        this.usageCharges = usageCharges;
        this.repository = repository;
        this.minio = minio;
        this.storage = storage;
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
    }

    // ponytail: 한 번에 한 건씩 처리한다. 업로드가 몰리면 작업자 동시 실행 수를 늘린다.
    @Scheduled(fixedDelayString = "${app.meeting.transcription-poll-interval-ms:2000}")
    public void processNext() {
        repository.claimTranscription(Instant.now().minus(STALE_CLAIM)).ifPresent(this::transcribe);
    }

    private void transcribe(MeetingRepository.Meeting meeting) {
        try {
            byte[] audio;
            try (InputStream input = minio.getObject(GetObjectArgs.builder()
                    .bucket(storage.getBucket()).object(meeting.recordingKey()).build())) {
                audio = input.readAllBytes();
            }
            JsonNode body = usageCharges.track("meeting_transcription", meeting.workspaceId(), meeting.createdBy(), runId -> {
                var uri = UriComponentsBuilder.fromUriString(endpoint)
                        .queryParam("workspace_id", "{workspaceId}").queryParam("user_id", "{userId}")
                        .queryParam("run_id", "{runId}")
                        .encode().buildAndExpand(meeting.workspaceId(), meeting.createdBy(), runId).toUri();
                return restClient.post().uri(uri).contentType(MediaType.parseMediaType(meeting.recordingContentType()))
                        .body(audio).retrieve().body(JsonNode.class);
            });
            String text = body == null ? "" : body.path("text").asText("").strip();
            if (text.isEmpty()) {
                repository.failTranscription(meeting.id(), meeting.recordingKey(), "인식된 음성이 없습니다.");
                return;
            }
            if (text.length() > MAX_TOTAL_CHARS) {
                repository.failTranscription(meeting.id(), meeting.recordingKey(),
                        "전사가 회의록 생성 한도(100,000자)를 넘었습니다. 녹음을 나눠 올려 주세요.");
                return;
            }
            repository.completeTranscription(meeting.id(), meeting.recordingKey(), segments(text));
        } catch (RestClientResponseException e) {
            log.warn("[회의 파일 전사 실패] meetingId={} status={}", meeting.id(), e.getStatusCode().value());
            repository.failTranscription(meeting.id(), meeting.recordingKey(), switch (e.getStatusCode().value()) {
                case 413, 415, 422 -> "지원하지 않거나 인식할 수 없는 녹음 파일입니다.";
                default -> "녹음 파일을 전사하지 못했습니다. 다시 올려 주세요.";
            });
        } catch (Exception e) {
            log.warn("[회의 파일 전사 실패] meetingId={}", meeting.id(), e);
            repository.failTranscription(meeting.id(), meeting.recordingKey(), "녹음 파일을 전사하지 못했습니다. 다시 올려 주세요.");
        }
    }

    /**
     * 문장을 이어 붙여 구간을 최대 약 1,000자로 묶는다. 이어진 두 구간의 합은 항상 1,000자를 넘으므로
     * 100,000자 전사는 구간 약 200개 이하가 되어 회의록 AI의 1,000구간 한도에 걸리지 않는다.
     */
    static List<String> segments(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences(text)) {
            if (!current.isEmpty() && current.length() + 1 + sentence.length() > TARGET_SEGMENT_CHARS) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    /** 문장 부호·줄바꿈으로 나누고, 회의록 AI의 구간 한도(10,000자)를 넘는 문장은 잘라서 나눈다. */
    static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text.strip())) {
            String trimmed = sentence.strip();
            for (int i = 0; i < trimmed.length(); i += MAX_SEGMENT_CHARS) {
                result.add(trimmed.substring(i, Math.min(trimmed.length(), i + MAX_SEGMENT_CHARS)));
            }
        }
        return result;
    }
}
