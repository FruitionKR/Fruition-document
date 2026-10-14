package fruition.core.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.core.aitask.repository.PipelineTaskCancellationClient;
import fruition.core.aitask.service.AiTaskResultConsumer;
import fruition.core.document.service.AiCommandOutboxPublisher;
import fruition.core.purge.DataPurgeService;
import fruition.shared.security.JwtTokenProvider;
import fruition.shared.util.StorageProperties;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 녹음 원본 직접 업로드(S3 multipart)·전사 command 발행·결과 이벤트 반영·취소·재생 주소·삭제를 실제 MinIO와 DB로 확인한다.
 * Kafka는 쓰지 않는다. command는 outbox 행으로, 결과는 consumer에 이벤트를 직접 넘겨 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MeetingRecordingIntegrationTest {

    static final String TEXT = "출시는 금요일로 하겠습니다. 민수가 점검을 맡나요?\n네, 맡겠습니다!";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired MinioClient minio;
    @Autowired StorageProperties storage;
    @Autowired AiTaskResultConsumer consumer;
    @Autowired DataPurgeService purge;
    @MockBean AiCommandOutboxPublisher outboxPublisher;
    @MockBean PipelineTaskCancellationClient pipelineCancellation;

    private final HttpClient http = HttpClient.newHttpClient();
    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + userId, "{\"role\":\"OWNER\",\"permissions\":[]}");
    }

    // ---------- 직접 업로드 → command

    @Test
    void completedUpload_enqueuesExactCommandWithOneReservation_andPlayable() throws Exception {
        String meetingId = createMeeting("upload");
        byte[] audio = "m4a-bytes".getBytes();
        JsonNode meeting = uploadRecording(meetingId, "audio/mp4", audio, 202);
        assertThat(meeting.path("status").asText()).isEqualTo("transcribing");

        String runId = runId(meetingId);
        String key = recordingKey(meetingId);
        assertThat(runId).matches("meeting_transcription:" + meetingId + ":[0-9a-f-]{36}");
        assertThat(key).matches("meetings/" + meetingId + "/recording-[0-9a-f-]{36}\\.m4a");

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT topic, message_key, payload FROM ai_command_outbox WHERE run_id = ?", runId);
        assertThat(outbox.get("topic")).isEqualTo("ai.maintenance.command");
        assertThat(outbox.get("message_key")).isEqualTo(runId);
        assertThat(objectMapper.readTree((String) outbox.get("payload"))).isEqualTo(objectMapper.readTree("""
                {"run_id":"%s","kind":"meeting_transcription","workspace_id":"%s","user_id":"%s",
                 "meeting_id":"%s","recording_key":"%s","content_type":"audio/mp4"}
                """.formatted(runId, workspaceId, userId, meetingId, key)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_entries WHERE run_id = ? AND type = 'reserve'",
                Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT kind, status FROM ai_task_runs WHERE id = ?", runId))
                .containsEntry("kind", "meeting_transcription").containsEntry("status", "running");

        String url = json(mockMvc.perform(get(base() + "/" + meetingId + "/recording-url").header("Authorization", bearer())),
                200).path("url").asText();
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray()).body())
                .isEqualTo(audio);
    }

    @Test
    void invalidStart_isRejectedBeforeAnyStorageWrite() throws Exception {
        String meetingId = createMeeting("upload");
        start(meetingId, "video/mp4", 5).andExpect(status().isUnsupportedMediaType());
        start(meetingId, "audio/wav", 0).andExpect(status().isUnprocessableEntity());
        start(meetingId, "audio/wav", MeetingRecordingService.MAX_BYTES + 1).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("MEETING_RECORDING_TOO_LARGE"));
        start(meetingId, "audio/webm;codecs=opus", 5).andExpect(status().isOk());  // 파라미터는 무시한다
        assertThat(meetingStatus(meetingId)).isEqualTo("awaiting_upload");
    }

    @Test
    void sizeIsCheckedAgainstCapAndDeclaredSize() {
        MeetingRecordingService.requireSize(MeetingRecordingService.MAX_BYTES, MeetingRecordingService.MAX_BYTES);
        assertThatThrownBy(() -> MeetingRecordingService.requireSize(0, 0)).hasMessageContaining("비어");
        assertThatThrownBy(() -> MeetingRecordingService.requireSize(MeetingRecordingService.MAX_BYTES + 1,
                MeetingRecordingService.MAX_BYTES + 1)).hasMessageContaining("500 MiB");
        assertThatThrownBy(() -> MeetingRecordingService.requireSize(4, 5)).hasMessageContaining("크기");
    }

    @Test
    void uploadedPartsThatDifferFromDeclaredSize_areRejectedAndMeetingUnchanged() throws Exception {
        String meetingId = createMeeting("upload");
        String ticket = json(start(meetingId, "audio/wav", 5), 200).path("ticket").asText();
        put(partUrl(meetingId, ticket, 1), new byte[]{1, 2, 3});
        complete(meetingId, ticket).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEETING_UPLOAD_REJECTED"));
        assertThat(meetingStatus(meetingId)).isEqualTo("awaiting_upload");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE payload LIKE ?", Integer.class,
                "%" + meetingId + "%")).isZero();
    }

    @Test
    void onlyOwnerWithTheMeetingsTicketCanUse_andAbortWorks() throws Exception {
        String meetingId = createMeeting("upload");
        String other = createMeeting("upload");
        String ticket = json(start(meetingId, "audio/wav", 3), 200).path("ticket").asText();

        String stranger = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + stranger, "{\"role\":\"OWNER\",\"permissions\":[]}");
        String strangerBearer = "Bearer " + jwtTokenProvider.generateAccessToken(stranger, stranger + "@x.com");
        mockMvc.perform(post(base() + "/" + meetingId + "/recording/uploads").header("Authorization", strangerBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content_type\":\"audio/wav\",\"size\":3}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(base() + "/" + meetingId + "/recording/uploads/complete").header("Authorization", strangerBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"" + ticket + "\"}"))
                .andExpect(status().isForbidden());
        // 다른 회의에 발급한 ticket은 쓸 수 없다.
        uploadPost(other, "parts", "{\"ticket\":\"" + ticket + "\",\"first_part\":1,\"count\":1}").andExpect(status().isForbidden());
        uploadPost(meetingId, "complete", "{\"ticket\":\"tampered\"}").andExpect(status().isBadRequest());

        uploadPost(meetingId, "abort", "{\"ticket\":\"" + ticket + "\"}").andExpect(status().isNoContent());
    }

    @Test
    void statusRules_matchTheOldUploadRules() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/wav", new byte[]{1}, 202);
        // 전사 중에는 올릴 수 없다.
        start(meetingId, "audio/wav", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_RECORDING_NOT_ALLOWED"));

        String live = createMeeting("live");
        redisTemplate.opsForValue().set("speech:live:" + live, "someone");
        start(live, "audio/webm", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_LIVE_IN_USE"));
        redisTemplate.delete("speech:live:" + live);

        // 실시간 회의는 전사 없이 원본만 보관한다(200, outbox 없음). 두 번째는 거절된다.
        uploadRecording(live, "audio/webm", new byte[]{1}, 200);
        assertThat(meetingStatus(live)).isEqualTo("open");
        assertThat(runId(live)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_outbox WHERE payload LIKE ?", Integer.class,
                "%" + live + "%")).isZero();
        start(live, "audio/webm", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_RECORDING_NOT_ALLOWED"));
        assertThat(storedKeys(live)).containsExactly(recordingKey(live));
    }

    // ---------- 결과 이벤트

    @Test
    void succeededEvent_savesSegmentsAndClosesRun_andDuplicateIsIgnored() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/mp4", new byte[]{1}, 202);
        String runId = runId(meetingId);

        consumer.consume(event(runId, meetingId, "succeeded", null, TEXT));
        consumer.consume(event(runId, meetingId, "succeeded", null, TEXT));  // Kafka 재전송

        JsonNode meeting = json(mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())), 200);
        assertThat(meeting.path("status").asText()).isEqualTo("open");
        assertThat(meeting.path("segments")).extracting(s -> s.path("id").asText() + "=" + s.path("text").asText())
                .containsExactly("s1_seg_0001=출시는 금요일로 하겠습니다. 민수가 점검을 맡나요? 네, 맡겠습니다!");
        assertThat(meeting.path("transcript_complete").asBoolean()).isTrue();
        assertThat(meeting.path("streams").size()).isEqualTo(1);
        assertThat(runStatus(runId)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_collect_queue WHERE run_id = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void failedEvent_setsKoreanMessagePerCode_andClosesRun() throws Exception {
        Map<String, String> expected = Map.of(
                "MEETING_RECORDING_TOO_LONG", "3시간",
                "MEETING_RECORDING_UNREADABLE", "인식할 수 없는 녹음 파일",
                "MEETING_TRANSCRIPT_EMPTY", "인식된 음성이 없습니다.",
                "MEETING_TRANSCRIPT_TOO_LONG", "100,000자",
                "provider_rate_limited", "요청이 많아",
                "provider_unavailable", "일시적인 문제",
                "deadline_exceeded", "너무 오래",
                "cancelled", "취소",
                "internal_error", "전사하지 못했습니다");
        for (var entry : expected.entrySet()) {
            String meetingId = createMeeting("upload");
            uploadRecording(meetingId, "audio/wav", new byte[]{1}, 202);
            String runId = runId(meetingId);

            consumer.consume(event(runId, meetingId, "failed", entry.getKey(), null));

            JsonNode meeting = json(mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())), 200);
            assertThat(meeting.path("status").asText()).as(entry.getKey()).isEqualTo("failed");
            assertThat(meeting.path("error").asText()).as(entry.getKey()).contains(entry.getValue());
            assertThat(runStatus(runId)).as(entry.getKey()).isEqualTo("failed");
        }
        // AI가 성공이라 했지만 전사가 비어 있으면 실패다.
        String empty = createMeeting("upload");
        uploadRecording(empty, "audio/wav", new byte[]{1}, 202);
        consumer.consume(event(runId(empty), empty, "succeeded", null, "  "));
        assertThat(meetingStatus(empty)).isEqualTo("failed");
    }

    @Test
    void reuploadAfterFailure_getsNewRunId_andLateEventOfOldRunIsIgnored() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/wav", new byte[]{1, 2}, 202);
        String firstRun = runId(meetingId);
        String firstKey = recordingKey(meetingId);
        consumer.consume(event(firstRun, meetingId, "failed", "MEETING_RECORDING_UNREADABLE", null));

        uploadRecording(meetingId, "audio/webm;codecs=opus", new byte[]{3}, 202);
        String secondRun = runId(meetingId);
        assertThat(secondRun).isNotEqualTo(firstRun);
        // 다른 형식으로 다시 올려도 이전 원본이 남지 않는다.
        assertThat(recordingKey(meetingId)).endsWith(".webm").isNotEqualTo(firstKey);
        assertThat(storedKeys(meetingId)).containsExactly(recordingKey(meetingId));

        // 첫 run의 늦은 이벤트(성공이든 재전송이든)는 두 번째 전사를 건드리지 않는다.
        consumer.consume(event(firstRun, meetingId, "succeeded", null, TEXT));
        assertThat(meetingStatus(meetingId)).isEqualTo("transcribing");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_segments WHERE meeting_id = ?", Integer.class, meetingId)).isZero();

        consumer.consume(event(secondRun, meetingId, "succeeded", null, TEXT));
        assertThat(meetingStatus(meetingId)).isEqualTo("open");
        assertThat(json(mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())), 200)
                .path("error").isNull()).isTrue();
    }

    @Test
    void eventForVanishedMeetingOrUnknownRun_isIgnoredAndOpenRunClosed() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/wav", new byte[]{1}, 202);
        String runId = runId(meetingId);
        // 파기로 회의가 사라졌지만 run은 남은 경우: 반영하지 않고 run만 닫아 호출분을 정산한다.
        jdbc.update("DELETE FROM meetings WHERE id = ?", meetingId);
        consumer.consume(event(runId, meetingId, "succeeded", null, TEXT));
        assertThat(runStatus(runId)).isEqualTo("failed");

        // run 등록 자체가 파기된 경우(예외 없이 무시해 consumer가 재시도 루프에 빠지지 않는다).
        String other = createMeeting("upload");
        uploadRecording(other, "audio/wav", new byte[]{1}, 202);
        String otherRun = runId(other);
        purge.purgeWorkspace(workspaceId);
        consumer.consume(event(otherRun, other, "succeeded", null, TEXT));
        consumer.consume(event("meeting_transcription:" + other + ":not-a-uuid", other, "succeeded", null, TEXT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meetings WHERE id = ?", Integer.class, other)).isZero();
    }

    // ---------- 취소·삭제

    @Test
    void cancel_failsMeetingFirst_requestsRunCancel_andLateResultIsIgnored() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/mp4", new byte[]{1}, 202);
        String runId = runId(meetingId);

        String stranger = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + stranger, "{\"role\":\"OWNER\",\"permissions\":[]}");
        mockMvc.perform(post(base() + "/" + meetingId + "/transcription/cancel")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(stranger, stranger + "@x.com")))
                .andExpect(status().isNotFound());
        // 전사 중에는 지울 수 없다. 취소하면 지울 수 있다.
        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_TRANSCRIBING"));

        mockMvc.perform(post(base() + "/" + meetingId + "/transcription/cancel").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value("전사를 취소했습니다. 녹음을 다시 올려 주세요."));
        verify(pipelineCancellation).cancel(eq(runId), eq(workspaceId), eq(userId), any());
        assertThat(runStatus(runId)).isEqualTo("cancel_requested");
        mockMvc.perform(post(base() + "/" + meetingId + "/transcription/cancel").header("Authorization", bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEETING_NOT_TRANSCRIBING"));

        consumer.consume(event(runId, meetingId, "succeeded", null, TEXT));
        assertThat(meetingStatus(meetingId)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_segments WHERE meeting_id = ?", Integer.class, meetingId)).isZero();

        String key = recordingKey(meetingId);
        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isNoContent());
        assertThatThrownBy(() -> minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build()))
                .hasMessageContaining("Object does not exist");
    }

    @Test
    void delete_removesRecordingAndRows_andRequiresOwnerAndNoLive() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/mp4", new byte[]{1}, 202);
        consumer.consume(event(runId(meetingId), meetingId, "succeeded", null, TEXT));
        String key = recordingKey(meetingId);

        String other = "user_" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set("authz:role:v2:" + workspaceId + ":" + other, "{\"role\":\"OWNER\",\"permissions\":[]}");
        mockMvc.perform(delete(base() + "/" + meetingId)
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(other, other + "@x.com")))
                .andExpect(status().isNotFound());

        redisTemplate.opsForValue().set("speech:live:" + meetingId, "someone");
        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEETING_LIVE_IN_USE"));
        redisTemplate.delete("speech:live:" + meetingId);

        mockMvc.perform(delete(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isNoContent());
        mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_segments WHERE meeting_id = ?", Integer.class, meetingId)).isZero();
        assertThatThrownBy(() -> minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket()).object(key).build()))
                .hasMessageContaining("Object does not exist");
    }

    // ---------- 구간 나누기

    @Test
    void longTranscript_isPackedUnderNotesLimits() throws Exception {
        String meetingId = createMeeting("upload");
        uploadRecording(meetingId, "audio/mp4", new byte[]{1}, 202);
        consumer.consume(event(runId(meetingId), meetingId, "succeeded", null, "네. ".repeat(3_000)));
        JsonNode meeting = json(mockMvc.perform(get(base() + "/" + meetingId).header("Authorization", bearer())), 200);
        assertThat(meeting.path("segments").size()).isBetween(1, 1_000);
        meeting.path("segments").forEach(s -> assertThat(s.path("text").asText().length()).isLessThanOrEqualTo(1_000));

        String tooLong = createMeeting("upload");
        uploadRecording(tooLong, "audio/mp4", new byte[]{1}, 202);
        consumer.consume(event(runId(tooLong), tooLong, "succeeded", null, "가".repeat(100_001)));
        assertThat(json(mockMvc.perform(get(base() + "/" + tooLong).header("Authorization", bearer())), 200)
                .path("error").asText()).contains("100,000자");
    }

    @Test
    void segments_packSentencesUpToTargetLength() {
        String sentence = "가".repeat(400) + ".";
        assertThat(MeetingTranscriptionService.segments(String.join(" ", sentence, sentence, sentence)))
                .extracting(String::length).containsExactly(803, 401);  // 두 문장+공백까지 1,000자 이하
        assertThat(MeetingTranscriptionService.segments("가".repeat(5_000))).extracting(String::length)
                .containsExactly(5_000);  // 한 문장이 길면 그대로(구간당 10,000자 한도 안)
        assertThat(MeetingTranscriptionService.segments("")).isEmpty();
    }

    @Test
    void sentences_splitOnPunctuationAndLongText() {
        assertThat(MeetingTranscriptionService.sentences("  가. 나?\n\n다!  라")).containsExactly("가.", "나?", "다!", "라");
        assertThat(MeetingTranscriptionService.sentences("")).isEmpty();
        assertThat(MeetingTranscriptionService.sentences("가".repeat(20_001))).extracting(String::length)
                .containsExactly(10_000, 10_000, 1);
    }

    // ---------- helpers

    private String createMeeting(String source) throws Exception {
        return json(mockMvc.perform(post(base()).header("Authorization", bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"녹음 회의\",\"source\":\"" + source + "\"}")), 201).path("meeting_id").asText();
    }

    private ResultActions start(String meetingId, String contentType, long size) throws Exception {
        return mockMvc.perform(post(base() + "/" + meetingId + "/recording/uploads").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content_type\":\"" + contentType + "\",\"size\":" + size + "}"));
    }

    private ResultActions uploadPost(String meetingId, String action, String body) throws Exception {
        return mockMvc.perform(post(base() + "/" + meetingId + "/recording/uploads/" + action)
                .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions complete(String meetingId, String ticket) throws Exception {
        return uploadPost(meetingId, "complete", "{\"ticket\":\"" + ticket + "\"}");
    }

    private String partUrl(String meetingId, String ticket, int part) throws Exception {
        return json(uploadPost(meetingId, "parts", "{\"ticket\":\"" + ticket + "\",\"first_part\":" + part + ",\"count\":1}"), 200)
                .path("parts").get(0).path("url").asText();
    }

    private void put(String url, byte[] bytes) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    /** start → 조각 PUT(테스트 파일은 한 조각) → complete. */
    private JsonNode uploadRecording(String meetingId, String contentType, byte[] bytes, int expectedStatus) throws Exception {
        String ticket = json(start(meetingId, contentType, bytes.length), 200).path("ticket").asText();
        put(partUrl(meetingId, ticket, 1), bytes);
        return json(complete(meetingId, ticket), expectedStatus);
    }

    private String event(String runId, String meetingId, String status, String error, String text) throws Exception {
        var event = objectMapper.createObjectNode().put("event_id", UUID.randomUUID().toString()).put("run_id", runId)
                .put("kind", "meeting_transcription").put("status", status)
                .put("workspace_id", workspaceId).put("user_id", userId);
        if (error != null) event.put("error", error);
        if (text != null) event.set("payload", objectMapper.createObjectNode().put("meeting_id", meetingId)
                .put("text", text).put("duration_seconds", 12.5).put("chunks", 1));
        return event.toString();
    }

    private String runId(String meetingId) {
        return jdbc.queryForObject("SELECT transcription_run_id FROM meetings WHERE id = ?", String.class, meetingId);
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM ai_task_runs WHERE id = ?", String.class, runId);
    }

    private String meetingStatus(String meetingId) {
        return jdbc.queryForObject("SELECT status FROM meetings WHERE id = ?", String.class, meetingId);
    }

    private String recordingKey(String meetingId) {
        return jdbc.queryForObject("SELECT recording_key FROM meetings WHERE id = ?", String.class, meetingId);
    }

    private List<String> storedKeys(String meetingId) throws Exception {
        List<String> keys = new java.util.ArrayList<>();
        for (var item : minio.listObjects(io.minio.ListObjectsArgs.builder().bucket(storage.getBucket())
                .prefix("meetings/" + meetingId + "/").recursive(true).build())) {
            keys.add(item.get().objectName());
        }
        return keys;
    }

    private String base() {
        return "/api/workspaces/" + workspaceId + "/meetings";
    }

    private JsonNode json(ResultActions result, int expectedStatus) throws Exception {
        var response = result.andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return objectMapper.readTree(response.getContentAsString());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");
    }
}
