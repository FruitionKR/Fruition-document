package fruition.core.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.aitask.service.AiTaskCancellationService;
import fruition.core.document.repository.AiCommandOutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 녹음 파일 회의의 전사. 업로드가 끝나면 {@code meeting_transcription} command를 AI 유지보수 큐로 보내고
 * ({@link #begin}), AI가 {@code ai.task.event}로 돌려준 결과를 문장 단위 구간으로 저장한다({@link #apply}).
 * run은 업로드마다 새로 만들며 회의 행의 {@code transcription_run_id}와 일치하는 이벤트만 반영한다.
 */
@Service
public class MeetingTranscriptionService {
    static final int MAX_SEGMENT_CHARS = 10_000;
    /** 회의록 AI는 구간 1,000개·전체 100,000자까지 받는다. 문장을 이 길이까지 묶어 구간 수를 한도 안에 둔다. */
    static final int TARGET_SEGMENT_CHARS = 1_000;
    static final int MAX_TOTAL_CHARS = 100_000;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.?!。？！])\\s+|\\n+");
    private static final Pattern RUN_ID = Pattern.compile("^meeting_transcription:([A-Za-z0-9_-]{1,128}):[0-9a-f-]{36}$");
    private static final Logger log = LoggerFactory.getLogger(MeetingTranscriptionService.class);

    private final MeetingRepository repository;
    private final MeetingService meetingService;
    private final AiCommandOutboxWriter outbox;
    private final AiTaskCancellationService cancellation;
    private final String commandTopic;

    public MeetingTranscriptionService(MeetingRepository repository, MeetingService meetingService,
                                       AiCommandOutboxWriter outbox, AiTaskCancellationService cancellation,
                                       @Value("${app.maintenance.command-topic}") String commandTopic) {
        this.repository = repository;
        this.meetingService = meetingService;
        this.outbox = outbox;
        this.cancellation = cancellation;
        this.commandTopic = commandTopic;
    }

    public static String newRunId(String meetingId) {
        return "meeting_transcription:" + meetingId + ":" + UUID.randomUUID();
    }

    /**
     * 호출자의 DB 트랜잭션에서 run 등록·크레딧 예약과 command outbox 저장을 한다. 잔액이 모자라면 예외로 트랜잭션이 롤백된다.
     * command의 키는 run_id다.
     */
    public void begin(String runId, MeetingRepository.Meeting meeting, String recordingKey, String contentType) {
        outbox.enqueue(runId, commandTopic, runId, new TranscriptionCommand(runId, "meeting_transcription",
                meeting.workspaceId(), meeting.createdBy(), meeting.id(), recordingKey, contentType));
    }

    /**
     * {@code ai.task.event}의 {@code meeting_transcription} 결과를 반영한다. 회의가 사라졌거나 취소·재업로드로
     * 다른 run이 된 경우, 이미 반영한 중복 이벤트는 회의를 바꾸지 않는다. 그 경우 아직 열려 있는 run만 닫아 호출분을 정산한다.
     */
    @Transactional
    public void apply(JsonNode event) {
        String runId = event.path("run_id").asText("");
        Matcher matcher = RUN_ID.matcher(runId);
        if (!matcher.matches()) {
            log.warn("[회의 전사 이벤트 무시] runId={} reason=invalid_run_id", runId);
            return;
        }
        String meetingId = matcher.group(1);
        // 취소·파기되어 더 받지 않는 run의 이벤트는 무시한다. 수집·정산은 취소와 파기 쪽이 맡는다.
        if (!outbox.join(runId)) {
            log.info("[회의 전사 이벤트 무시] meetingId={} reason=run_not_active", meetingId);
            return;
        }
        String error = "succeeded".equals(event.path("status").asText()) ? null : event.path("error").asText("");
        String text = event.path("payload").path("text").asText("").strip();
        if (error == null && text.isEmpty()) error = "MEETING_TRANSCRIPT_EMPTY";
        if (error == null && text.length() > MAX_TOTAL_CHARS) error = "MEETING_TRANSCRIPT_TOO_LONG";

        boolean applied = error == null
                ? repository.completeTranscription(meetingId, runId, segments(text))
                : repository.failTranscription(meetingId, runId, failureMessage(error));
        if (!applied) log.info("[회의 전사 이벤트 무시] meetingId={} reason=meeting_changed", meetingId);
        // 반영 여부와 무관하게 닫는다. 이미 닫힌 run이면 아무것도 하지 않는다.
        if (error == null && applied) outbox.complete(runId);
        else outbox.fail(runId);
        if (error != null) log.warn("[회의 전사 실패 반영] meetingId={} code={}", meetingId, error);
    }

    /**
     * 전사 중인 회의를 사용자가 취소한다. 회의를 먼저 실패로 바꿔 늦게 오는 결과 이벤트가 반영되지 않게 하고,
     * 이어서 AI run 취소를 요청한다(취소 요청은 먼저 기록되어 AI 호출이 실패해도 주기 작업이 이어서 진행한다).
     */
    public MeetingResponse cancel(String workspaceId, String userId, String meetingId) {
        MeetingRepository.Meeting meeting = meetingService.requireOwned(workspaceId, userId, meetingId);
        String runId = meeting.transcriptionRunId();
        if (!"transcribing".equals(meeting.status()) || runId == null
                || !repository.failTranscription(meetingId, runId, "전사를 취소했습니다. 녹음을 다시 올려 주세요.")) {
            throw new MeetingException(HttpStatus.CONFLICT, "MEETING_NOT_TRANSCRIBING", "전사 중인 회의가 아닙니다.");
        }
        cancellation.cancel(runId, workspaceId, userId);
        return meetingService.get(workspaceId, userId, meetingId);
    }

    /** AI가 준 오류 코드로만 문구를 만든다. 예외 원문은 싣지 않는다. */
    static String failureMessage(String code) {
        return switch (code == null ? "" : code) {
            case "MEETING_RECORDING_TOO_LONG" -> "녹음이 3시간을 넘어 전사할 수 없습니다. 나눠서 올려 주세요.";
            case "MEETING_RECORDING_UNREADABLE", "input_rejected" -> "지원하지 않거나 인식할 수 없는 녹음 파일입니다.";
            case "MEETING_TRANSCRIPT_EMPTY" -> "인식된 음성이 없습니다.";
            case "MEETING_TRANSCRIPT_TOO_LONG" -> "전사가 회의록 생성 한도(100,000자)를 넘었습니다. 녹음을 나눠 올려 주세요.";
            case "provider_rate_limited" -> "AI 공급사의 요청 한도를 넘었습니다. 잠시 뒤 다시 올려 주세요.";
            case "provider_unavailable" -> "AI 공급사가 일시적으로 응답하지 않습니다. 잠시 뒤 다시 올려 주세요.";
            case "deadline_exceeded" -> "전사 시간이 너무 오래 걸려 중단했습니다. 녹음을 나눠 올려 주세요.";
            case "cancelled" -> "전사가 취소되었습니다.";
            default -> "녹음 파일을 전사하지 못했습니다. 다시 올려 주세요.";
        };
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

    record TranscriptionCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("meeting_id") String meetingId,
            @JsonProperty("recording_key") String recordingKey,
            @JsonProperty("content_type") String contentType
    ) {}
}
